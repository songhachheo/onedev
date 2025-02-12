package io.onedev.server.git;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.shiro.authz.UnauthorizedException;
import org.eclipse.jgit.http.server.GitSmartHttpTools;
import org.eclipse.jgit.http.server.ServletUtils;
import org.eclipse.jgit.transport.PacketLineOut;
import org.glassfish.jersey.client.ClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.onedev.commons.bootstrap.Bootstrap.BUFFER_SIZE;
import io.onedev.k8shelper.KubernetesHelper;
import io.onedev.server.OneDev;
import io.onedev.server.cluster.ClusterManager;
import io.onedev.server.entitymanager.ProjectManager;
import io.onedev.server.exception.SystemNotReadyException;
import io.onedev.server.git.command.AdvertiseReceiveRefsCommand;
import io.onedev.server.git.command.AdvertiseUploadRefsCommand;
import io.onedev.server.git.exception.GitException;
import io.onedev.server.git.hook.HookUtils;
import io.onedev.server.model.Project;
import io.onedev.server.model.User;
import io.onedev.server.persistence.SessionManager;
import io.onedev.server.security.CodePullAuthorizationSource;
import io.onedev.server.security.SecurityUtils;
import io.onedev.server.storage.StorageManager;
import io.onedev.server.util.InputStreamWrapper;
import io.onedev.server.util.OutputStreamWrapper;
import io.onedev.server.util.concurrent.PrioritizedRunnable;
import io.onedev.server.util.concurrent.WorkExecutor;
import io.onedev.server.util.facade.ProjectFacade;

@Singleton
public class GitFilter implements Filter {
	
	private static final Logger logger = LoggerFactory.getLogger(GitFilter.class);

	public static final int PRIORITY = 2;
	
	private static final String INFO_REFS = "info/refs";
	
	private final OneDev onedev;
	
	private final StorageManager storageManager;
	
	private final ProjectManager projectManager;
	
	private final WorkExecutor workExecutor;
	
	private final SessionManager sessionManager;
	
	private final ClusterManager clusterManager;
	
	private final Set<CodePullAuthorizationSource> codePullAuthorizationSources;
	
	@Inject
	public GitFilter(OneDev oneDev, StorageManager storageManager, ProjectManager projectManager, 
			WorkExecutor workExecutor, SessionManager sessionManager, ClusterManager clusterManager, 
			Set<CodePullAuthorizationSource> codePullAuthorizationSources) {
		this.onedev = oneDev;
		this.storageManager = storageManager;
		this.projectManager = projectManager;
		this.workExecutor = workExecutor;
		this.sessionManager = sessionManager;
		this.clusterManager = clusterManager;
		this.codePullAuthorizationSources = codePullAuthorizationSources;
	}
	
	private String getPathInfo(HttpServletRequest request) {
		String pathInfo = request.getRequestURI().substring(request.getContextPath().length());
		return StringUtils.stripStart(pathInfo, "/");
	}
	
	private Long getProjectId(String projectInfo) throws IOException {
		String projectPath = StringUtils.strip(projectInfo, "/");

		ProjectFacade project = projectManager.findFacadeByPath(projectPath);
		if (project == null && projectPath.startsWith("projects/")) {
			projectPath = projectPath.substring("projects/".length());
			project = projectManager.findFacadeByPath(projectPath);
		}
		if (project == null) 
			throw new GitException(String.format("Unable to find project '%s'", projectPath));
		return project.getId();
	}
	
	private void doNotCache(HttpServletResponse response) {
		response.setHeader("Expires", "Fri, 01 Jan 1980 00:00:00 GMT");
		response.setHeader("Pragma", "no-cache");
		response.setHeader("Cache-Control", "no-cache, max-age=0, must-revalidate");
	}
	
	protected void processPack(final HttpServletRequest request, final HttpServletResponse response) 
			throws ServletException, IOException, InterruptedException, ExecutionException {
		Long userId = SecurityUtils.getUserId();
		
		String pathInfo = getPathInfo(request);
		
		String service = StringUtils.substringAfterLast(pathInfo, "/");

		String projectInfo = StringUtils.substringBeforeLast(pathInfo, "/");
		Long projectId = getProjectId(projectInfo);
		
		doNotCache(response);
		response.setHeader("Content-Type", "application/x-" + service + "-result");			

		var hookEnvs = HookUtils.getHookEnvs(projectId, userId);
		
		InputStream stdin = new InputStreamWrapper(ServletUtils.getInputStream(request)) {

			@Override
			public void close() throws IOException {
			}
			
		};
		OutputStream stdout = new OutputStreamWrapper(response.getOutputStream()) {
			
			@Override
			public void close() throws IOException {
			}
			
		};
		
		String protocol = request.getHeader("Git-Protocol");		
		
		if (!userId.equals(User.SYSTEM_ID)) {
			boolean upload;
			sessionManager.openSession();
			try {
				Project project = projectManager.load(projectId);
				if (GitSmartHttpTools.isUploadPack(request)) {
					checkPullPermission(request, project);
					upload = true;
				} else {
					if (!SecurityUtils.canWriteCode(project))
						throw new UnauthorizedException("You do not have permission to push to this project.");
					upload = false;
				}			
			} finally {
				sessionManager.closeSession();
			}
			
			UUID storageServerUUID = projectManager.getStorageServerUUID(projectId, true);
			if (storageServerUUID.equals(clusterManager.getLocalServerUUID())) {
				File gitDir = storageManager.getProjectGitDir(projectId);
				if (upload) {
					workExecutor.submit(new PrioritizedRunnable(PRIORITY) {
						
						@Override
						public void run() {
							CommandUtils.uploadPack(gitDir, hookEnvs, protocol, stdin, stdout);
						}
						
					}).get();
				} else {
					workExecutor.submit(new PrioritizedRunnable(PRIORITY) {
						
						@Override
						public void run() {
							CommandUtils.receivePack(gitDir, hookEnvs, protocol, stdin, stdout);
						}
						
					}).get();
				}
			} else {
				Client client = ClientBuilder.newClient();
				client.property(ClientProperties.REQUEST_ENTITY_PROCESSING, "CHUNKED");
				try {
					String serverUrl = clusterManager.getServerUrl(storageServerUUID);
					WebTarget target = client.target(serverUrl)
							.path("api/cluster/git-pack")
							.queryParam("projectId", projectId)
							.queryParam("userId", userId)
							.queryParam("protocol", protocol)
							.queryParam("upload", upload);
					Invocation.Builder builder =  target.request();
					builder.header(HttpHeaders.AUTHORIZATION, 
							KubernetesHelper.BEARER + " " + clusterManager.getCredentialValue());
					
					StreamingOutput os = new StreamingOutput() {

						@Override
						public void write(OutputStream output) throws IOException {
							try {
								byte[] buffer = new byte[BUFFER_SIZE];
						        int length;
					            while ((length = stdin.read(buffer)) > 0) {
				            		output.write(buffer, 0, length);
				            		output.flush();
					            }
							} finally {
								stdin.close();
							}
						}				   
					   
					};
					
					try (Response gitResponse = builder.post(Entity.entity(os, MediaType.APPLICATION_OCTET_STREAM))) {
						KubernetesHelper.checkStatus(gitResponse);
						try (InputStream is = gitResponse.readEntity(InputStream.class)) {
							byte[] buffer = new byte[BUFFER_SIZE];
					        int length;
				            while ((length = is.read(buffer)) > 0) {
			            		stdout.write(buffer, 0, length);
			            		stdout.flush();
				            }
						} finally {
							stdout.close();
						}
					}
				} finally {
					client.close();
				}
			}
		} else {
			File gitDir = storageManager.getProjectGitDir(projectId);
			if (GitSmartHttpTools.isUploadPack(request)) { 
				// Run immediately if accessed with cluster credential to avoid 
				// possible deadlock as caller itself might also hold some 
				// resources (db connections, work executors etc) 
				CommandUtils.uploadPack(gitDir, hookEnvs, protocol, stdin, stdout);
			} else {
				// Run immediately. See above for reason
				CommandUtils.receivePack(gitDir, hookEnvs, protocol, stdin, stdout);
			}			
		}
	}
	
	private void writeInitial(HttpServletResponse response, String service) throws IOException {
		doNotCache(response);
		response.setHeader("Content-Type", "application/x-" + service + "-advertisement");			
		
		PacketLineOut pack = new PacketLineOut(response.getOutputStream());
		pack.setFlushOnEnd(false);
		pack.writeString("# service=" + service + "\n");
		pack.end();
	}
	
	private void checkPullPermission(HttpServletRequest request, Project project) {
		if (!SecurityUtils.canReadCode(project)) {
			boolean isAuthorized = false;
			for (CodePullAuthorizationSource source: codePullAuthorizationSources) {
				if (source.canPullCode(request, project)) {
					isAuthorized = true;
					break;
				}
			}
			if (!isAuthorized)
				throw new UnauthorizedException("You do not have permission to pull from this project.");
		}
	}
	
	protected void processRefs(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		Long userId = SecurityUtils.getUserId();
		
		String pathInfo = request.getRequestURI().substring(request.getContextPath().length());
		pathInfo = StringUtils.stripStart(pathInfo, "/");

		String projectInfo = pathInfo.substring(0, pathInfo.length() - INFO_REFS.length());
		Long projectId = getProjectId(projectInfo);
		String service = request.getParameter("service");
		
		boolean upload;
		if (!userId.equals(User.SYSTEM_ID)) {
			sessionManager.openSession();
			try {
				Project project = projectManager.load(projectId);
				if (service.contains("upload")) {
					checkPullPermission(request, project);
					writeInitial(response, service);
					upload = true;
				} else {
					if (!SecurityUtils.canWriteCode(project))
						throw new UnauthorizedException("You do not have permission to push to this project.");
					writeInitial(response, service);
					upload = false;
				}
			} finally {
				sessionManager.closeSession();
			}
		} else { // cluster access, avoid accessing database
			writeInitial(response, service);
			upload = service.contains("upload");
		}
		
		OutputStream output = new OutputStreamWrapper(response.getOutputStream()) {

			@Override
			public void close() throws IOException {
			}
			
		};
		
		String protocol = request.getHeader("Git-Protocol");		

		UUID storageServerUUID = projectManager.getStorageServerUUID(projectId, true);
		if (storageServerUUID.equals(clusterManager.getLocalServerUUID())) {
			File gitDir = storageManager.getProjectGitDir(projectId);
			if (upload) 
				new AdvertiseUploadRefsCommand(gitDir, output).protocol(protocol).run();
			else 
				new AdvertiseReceiveRefsCommand(gitDir, output).protocol(protocol).run();
		} else {
			Client client = ClientBuilder.newClient();
			try {
				String serverUrl = clusterManager.getServerUrl(storageServerUUID);
				WebTarget target = client.target(serverUrl)
						.path("api/cluster/git-advertise-refs")
						.queryParam("projectId", projectId)
						.queryParam("protocol", protocol)
						.queryParam("upload", upload);
				Invocation.Builder builder =  target.request();
				builder.header(HttpHeaders.AUTHORIZATION, 
						KubernetesHelper.BEARER + " " + clusterManager.getCredentialValue());
				try (Response gitResponse = builder.get()) {
					KubernetesHelper.checkStatus(gitResponse);
					try (
							InputStream is = new BufferedInputStream(
									gitResponse.readEntity(InputStream.class), BUFFER_SIZE);
							OutputStream os = new BufferedOutputStream(output, BUFFER_SIZE);) {
						IOUtils.copy(is, os);
					}
				}
			} finally {
				client.close();
			}
		}
	}

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response,
			FilterChain chain) throws IOException, ServletException {
		HttpServletRequest httpRequest = (HttpServletRequest) request;
		HttpServletResponse httpResponse = (HttpServletResponse) response;
		
		try {
			if (GitSmartHttpTools.isInfoRefs(httpRequest)) {
				if (onedev.isReady())
					processRefs(httpRequest, httpResponse);
				else
					throw new SystemNotReadyException();
			} else if (GitSmartHttpTools.isReceivePack(httpRequest) || GitSmartHttpTools.isUploadPack(httpRequest)) {
				if (onedev.isReady())
					processPack(httpRequest, httpResponse);
				else
					throw new SystemNotReadyException();
			} else {
				chain.doFilter(request, response);
			}
		} catch (SystemNotReadyException e) {
			logger.debug("Unable to serve git request as system is not ready yet");
			GitSmartHttpTools.sendError(httpRequest, httpResponse, HttpServletResponse.SC_SERVICE_UNAVAILABLE, e.getMessage());
		} catch (GitException|InterruptedException|ExecutionException e) {
			logger.error("Error serving git request", e);
			GitSmartHttpTools.sendError(httpRequest, httpResponse, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
		}
	}
	
	@Override
	public void destroy() {
	}
	
}
 