package io.onedev.server.cluster;

import static io.onedev.commons.bootstrap.Bootstrap.BUFFER_SIZE;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.compress.utils.IOUtils;
import org.apache.shiro.authz.UnauthorizedException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

import com.google.common.collect.Sets;

import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.LockUtils;
import io.onedev.server.attachment.AttachmentManager;
import io.onedev.server.entitymanager.ProjectManager;
import io.onedev.server.git.CommandUtils;
import io.onedev.server.git.GitFilter;
import io.onedev.server.git.GitUtils;
import io.onedev.server.git.LfsObject;
import io.onedev.server.git.command.AdvertiseReceiveRefsCommand;
import io.onedev.server.git.command.AdvertiseUploadRefsCommand;
import io.onedev.server.git.hook.HookUtils;
import io.onedev.server.infomanager.CommitInfoManager;
import io.onedev.server.model.Build;
import io.onedev.server.rest.annotation.Api;
import io.onedev.server.security.SecurityUtils;
import io.onedev.server.storage.StorageManager;
import io.onedev.server.util.concurrent.PrioritizedRunnable;
import io.onedev.server.util.concurrent.WorkExecutor;
import io.onedev.server.util.patternset.PatternSet;

@Api(internal=true)
@Path("/cluster")
@Consumes(MediaType.WILDCARD)
@Singleton
public class ClusterResource {

	private final ProjectManager projectManager;
	
	private final AttachmentManager attachmentManager;
	
	private final StorageManager storageManager;
	
	private final CommitInfoManager commitInfoManager;
	
	private final WorkExecutor workExecutor;
	
	@Inject
	public ClusterResource(ProjectManager projectManager, CommitInfoManager commitInfoManager, 
			StorageManager storageManager, AttachmentManager attachmentManager, 
			WorkExecutor workExecutor) {
		this.projectManager = projectManager;
		this.commitInfoManager = commitInfoManager;
		this.storageManager = storageManager;
		this.workExecutor = workExecutor;
		this.attachmentManager = attachmentManager;
	}
	
	@Path("/artifacts")
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	@GET
	public Response downloadArtifacts(@QueryParam("projectId") Long projectId,
			@QueryParam("buildNumber") Long buildNumber,
			@QueryParam("artifacts") String artifacts) {
		if (!SecurityUtils.getUser().isSystem()) 
			throw new UnauthorizedException("This api can only be accessed via cluster credential");
		
		StreamingOutput os = new StreamingOutput() {

			@Override
		   public void write(OutputStream output) throws IOException {
				LockUtils.read(Build.getArtifactsLockName(projectId, buildNumber), new Callable<Void>() {

					@Override
					public Void call() throws Exception {
						File artifactsDir = Build.getArtifactsDir(projectId, buildNumber);
						PatternSet patternSet = PatternSet.parse(artifacts);
						FileUtils.tar(artifactsDir, patternSet.getIncludes(), patternSet.getExcludes(), output, false);
						return null;
					}
					
				});
		   }				   
		   
		};
		return Response.ok(os).build();
	}
	
	@Path("/blob")
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	@GET
	public Response downloadBlob(@QueryParam("projectId") Long projectId, @QueryParam("revId") String revId, 
			@QueryParam("path") String path) {
		if (!SecurityUtils.getUser().isSystem()) 
			throw new UnauthorizedException("This api can only be accessed via cluster credential");
		
		StreamingOutput os = new StreamingOutput() {

			@Override
		   public void write(OutputStream output) throws IOException {
				Repository repository = projectManager.getRepository(projectId);
				try (
						InputStream is = new BufferedInputStream(
								GitUtils.getInputStream(repository, ObjectId.fromString(revId), path), 
								BUFFER_SIZE);
						OutputStream os = new BufferedOutputStream(output, BUFFER_SIZE)) {
					IOUtils.copy(is, os);
				}
		   }				   
		   
		};
		return Response.ok(os).build();
	}
	
	@Path("/git-advertise-refs")
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	@GET
	public Response gitAdvertiseRefs(@QueryParam("projectId") Long projectId, 
			@QueryParam("protocol") String protocol, @QueryParam("upload") boolean upload) {
		if (!SecurityUtils.getUser().isSystem()) 
			throw new UnauthorizedException("This api can only be accessed via cluster credential");
		
		StreamingOutput os = new StreamingOutput() {

			@Override
		   public void write(OutputStream output) throws IOException {
				File gitDir = storageManager.getProjectGitDir(projectId);
				if (upload)
					new AdvertiseUploadRefsCommand(gitDir, output).protocol(protocol).run();
				else
					new AdvertiseReceiveRefsCommand(gitDir, output).protocol(protocol).run();
		   }				   
		   
		};
		return Response.ok(os).build();
	}
		
	@Path("/git-pack")
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	@POST
	public Response gitPack(InputStream is, @QueryParam("projectId") Long projectId, 
			@QueryParam("userId") Long userId, @QueryParam("protocol") String protocol, 
			@QueryParam("upload") boolean upload) {
		if (!SecurityUtils.getUser().isSystem()) 
			throw new UnauthorizedException("This api can only be accessed via cluster credential");
		
		StreamingOutput os = new StreamingOutput() {

			@Override
		   public void write(OutputStream output) throws IOException {
				Map<String, String> hookEnvs = HookUtils.getHookEnvs(projectId, userId);
				
				try {
					File gitDir = storageManager.getProjectGitDir(projectId);
					if (upload) {
						workExecutor.submit(new PrioritizedRunnable(GitFilter.PRIORITY) {
							
							@Override
							public void run() {
								CommandUtils.uploadPack(gitDir, hookEnvs, protocol, is, output);
							}
							
						}).get();
					} else {
						workExecutor.submit(new PrioritizedRunnable(GitFilter.PRIORITY) {
							
							@Override
							public void run() {
								CommandUtils.receivePack(gitDir, hookEnvs, protocol, is, output);
							}
							
						}).get();
					}
				} catch (InterruptedException | ExecutionException e) {
					throw new RuntimeException(e);
				}
		   }				   
		   
		};
		return Response.ok(os).build();
	}
	
	@Path("/commit-info")
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	@GET
	public Response downloadCommitInfo(@QueryParam("projectId") Long projectId) {
		if (!SecurityUtils.getUser().isSystem()) 
			throw new UnauthorizedException("This api can only be accessed via cluster credential");
		
		StreamingOutput os = new StreamingOutput() {

			@Override
		   public void write(OutputStream output) throws IOException {
				File tempDir = FileUtils.createTempDir("commit-info"); 
				try {
					commitInfoManager.export(projectId, tempDir);
					FileUtils.tar(tempDir, Sets.newHashSet("**"), Sets.newHashSet(), output, false);
				} finally {
					FileUtils.deleteDir(tempDir);
				}
		   }				   
		   
		};
		return Response.ok(os).build();
	}
	
	@Path("/lfs")
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	@GET
	public Response downloadLfs(@QueryParam("projectId") Long projectId, @QueryParam("objectId") String objectId) {
		if (!SecurityUtils.getUser().isSystem()) 
			throw new UnauthorizedException("This api can only be accessed via cluster credential");
		
		StreamingOutput os = new StreamingOutput() {

			@Override
		   public void write(OutputStream output) throws IOException {
				try (
						InputStream is = new BufferedInputStream(
								new LfsObject(projectId, objectId).getInputStream(), 
								BUFFER_SIZE);
						OutputStream os = new BufferedOutputStream(output, BUFFER_SIZE);) {
					IOUtils.copy(is, os);
				}
		   }				   
		   
		};
		return Response.ok(os).build();
	}
	
	@Path("/lfs")
	@Consumes(MediaType.APPLICATION_OCTET_STREAM)
	@POST
	public Response uploadLfs(InputStream input, @QueryParam("projectId") Long projectId, 
			@QueryParam("objectId") String objectId) {
		if (!SecurityUtils.getUser().isSystem()) 
			throw new UnauthorizedException("This api can only be accessed via cluster credential");
		
		try (
				InputStream is = new BufferedInputStream(input, BUFFER_SIZE);
				OutputStream os = new BufferedOutputStream(
						new LfsObject(projectId, objectId).getOutputStream(), 
						BUFFER_SIZE);) {
			IOUtils.copy(is, os);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return Response.ok().build();
	}

	@Path("/attachment")
	@Consumes(MediaType.APPLICATION_OCTET_STREAM)
	@POST
	public Response uploadAttachment(InputStream input, @QueryParam("projectId") Long projectId, 
			@QueryParam("attachmentGroup") String attachmentGroup, 
			@QueryParam("suggestedAttachmentName") String suggestedAttachmentName) {
		if (!SecurityUtils.getUser().isSystem()) 
			throw new UnauthorizedException("This api can only be accessed via cluster credential");

		String attachmentName = attachmentManager.saveAttachmentLocal(
				projectId, attachmentGroup, suggestedAttachmentName, input);
		return Response.ok(attachmentName).build();
	}
	
	@Path("/attachments")
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	@GET
	public Response downloadAttachments(@QueryParam("projectId") Long projectId, 
			@QueryParam("attachmentGroup") String attachmentGroup) {
		if (!SecurityUtils.getUser().isSystem()) 
			throw new UnauthorizedException("This api can only be accessed via cluster credential");

		StreamingOutput os = new StreamingOutput() {

			@Override
		   public void write(OutputStream output) throws IOException {
				FileUtils.tar(attachmentManager.getAttachmentGroupDirLocal(projectId, attachmentGroup), 
						Sets.newHashSet("**"), Sets.newHashSet(), output, false);
		   }				   
		   
		};
		return Response.ok(os).build();
	}
	
	@Path("/artifact")
	@Consumes(MediaType.APPLICATION_OCTET_STREAM)
	@POST
	public Response uploadArtifact(InputStream input, @QueryParam("projectId") Long projectId, 
			@QueryParam("buildNumber") Long buildNumber,  @QueryParam("artifactPath") String artifactPath) {
		if (!SecurityUtils.getUser().isSystem()) 
			throw new UnauthorizedException("This api can only be accessed via cluster credential");

		LockUtils.write(Build.getArtifactsLockName(projectId, buildNumber), new Callable<Void>() {

			@Override
			public Void call() throws Exception {
				storageManager.initArtifactsDir(projectId, buildNumber);
				File artifactFile = new File(Build.getArtifactsDir(projectId, buildNumber), artifactPath);
				FileUtils.createDir(artifactFile.getParentFile());
				try (
						InputStream is = new BufferedInputStream(input, BUFFER_SIZE);
						OutputStream os = new BufferedOutputStream(new FileOutputStream(artifactFile), BUFFER_SIZE);) {
					IOUtils.copy(is, os);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				return null;
			}
			
		});
		
		return Response.ok().build();
	}
	
}
