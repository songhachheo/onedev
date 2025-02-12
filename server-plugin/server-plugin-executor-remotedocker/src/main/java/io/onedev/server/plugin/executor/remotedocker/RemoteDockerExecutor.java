package io.onedev.server.plugin.executor.remotedocker;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import org.apache.wicket.protocol.ws.api.IWebSocketConnection;
import org.eclipse.jetty.websocket.api.Session;

import io.onedev.agent.AgentData;
import io.onedev.agent.Message;
import io.onedev.agent.MessageTypes;
import io.onedev.agent.WebsocketUtils;
import io.onedev.agent.job.DockerJobData;
import io.onedev.agent.job.TestDockerJobData;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.server.OneDev;
import io.onedev.server.buildspec.Service;
import io.onedev.server.cluster.ClusterManager;
import io.onedev.server.job.AgentInfo;
import io.onedev.server.job.JobContext;
import io.onedev.server.job.ResourceAllocator;
import io.onedev.server.job.ResourceRunnable;
import io.onedev.server.job.log.LogManager;
import io.onedev.server.job.log.LogTask;
import io.onedev.server.model.support.RegistryLogin;
import io.onedev.server.plugin.executor.serverdocker.ServerDockerExecutor;
import io.onedev.server.search.entity.agent.AgentQuery;
import io.onedev.server.terminal.RemoteSession;
import io.onedev.server.terminal.ShellSession;
import io.onedev.server.util.CollectionUtils;
import io.onedev.server.web.editable.annotation.Editable;

@Editable(order=210, description="This executor runs build jobs as docker containers on remote machines via <a href='/administration/agents' target='_blank'>agents</a>")
public class RemoteDockerExecutor extends ServerDockerExecutor {

	private static final long serialVersionUID = 1L;
	
	private String agentQuery;
	
	private boolean mountDockerSock;
	
	private transient volatile Session agentSession;
	
	@Editable(order=390, name="Agent Selector", placeholder="Any agent", 
			description="Specify agents applicable for this executor")
	@io.onedev.server.web.editable.annotation.AgentQuery(forExecutor=true)
	public String getAgentQuery() {
		return agentQuery;
	}

	public void setAgentQuery(String agentQuery) {
		this.agentQuery = agentQuery;
	}

	@Editable(order=400, description="Whether or not to mount docker sock into job container to "
			+ "support docker operations in job commands, for instance to build docker image.<br>"
			+ "<b class='text-danger'>WARNING</b>: Malicious jobs can take control of the agent "
			+ "running the job by operating the mounted docker sock. You should configure "
			+ "job authorization below to make sure the executor can only be used by trusted "
			+ "jobs if this option is enabled")
	public boolean isMountDockerSock() {
		return mountDockerSock;
	}

	public void setMountDockerSock(boolean mountDockerSock) {
		this.mountDockerSock = mountDockerSock;
	}
	
	@Override
	public AgentQuery getAgentRequirement() {
		return AgentQuery.parse(agentQuery, true);
	}
	
	@Override
	public void execute(JobContext jobContext, TaskLogger jobLogger, AgentInfo agentInfo) {
		jobLogger.log(String.format("Executing job (executor: %s, agent: %s)...", 
				getName(), agentInfo.getData().getName()));

		List<Map<String, String>> registryLogins = new ArrayList<>();
		for (RegistryLogin login: getRegistryLogins()) {
			registryLogins.add(CollectionUtils.newHashMap(
					"url", login.getRegistryUrl(), 
					"userName", login.getUserName(), 
					"password", login.getPassword()));
		}
		
		List<Map<String, Serializable>> services = new ArrayList<>();
		for (Service service: jobContext.getServices())
			services.add(service.toMap());

		String jobToken = jobContext.getJobToken();
		List<String> trustCertContent = getTrustCertContent();
		DockerJobData jobData = new DockerJobData(jobToken, getName(), jobContext.getProjectPath(), 
				jobContext.getProjectId(), jobContext.getRefName(), jobContext.getCommitId().name(), 
				jobContext.getBuildNumber(), jobContext.getActions(), jobContext.getRetried(), 
				services, registryLogins, mountDockerSock, trustCertContent, getRunOptions());
		
		agentSession = agentInfo.getSession();
		try {
			WebsocketUtils.call(agentSession, jobData, 0);
		} catch (InterruptedException | TimeoutException e) {
			new Message(MessageTypes.CANCEL_JOB, jobToken).sendBy(agentSession);
		}
	}
	
	@Override
	public void test(TestData testData, TaskLogger jobLogger) {
		String jobToken = UUID.randomUUID().toString();
		UUID localServerUUID = OneDev.getInstance(ClusterManager.class).getLocalServerUUID();
		LogManager logManager = OneDev.getInstance(LogManager.class);
		logManager.addJobLogger(jobToken, jobLogger);
		try {
			OneDev.getInstance(ResourceAllocator.class).run(
					new TestRunnable(jobToken, this, testData, localServerUUID), 
					getAgentRequirement(), new HashMap<>());
		} finally {
			logManager.removeJobLogger(jobToken);
		}
	}

	private void testLocal(String jobToken, AgentInfo agentInfo, 
			TestData testData, UUID dispatcherMemberUUID) {
		TaskLogger jobLogger = new TaskLogger() {

			@Override
			public void log(String message, String sessionId) {
				OneDev.getInstance(ClusterManager.class).runOnServer(
						dispatcherMemberUUID, new LogTask(jobToken, message, sessionId));
			}
			
		};
		
		AgentData agentData = agentInfo.getData();
		Session agentSession = agentInfo.getSession();
		
		jobLogger.log(String.format("Testing on agent '%s'...", agentData.getName()));

		List<Map<String, String>> registryLogins = new ArrayList<>();
		for (RegistryLogin login: getRegistryLogins()) {
			registryLogins.add(CollectionUtils.newHashMap(
					"url", login.getRegistryUrl(), 
					"userName", login.getUserName(), 
					"password", login.getPassword()));
		}
		
		TestDockerJobData jobData = new TestDockerJobData(getName(), jobToken, 
				testData.getDockerImage(), registryLogins, getRunOptions());
		
		try {
			WebsocketUtils.call(agentSession, jobData, 0);
		} catch (InterruptedException | TimeoutException e) {
			new Message(MessageTypes.CANCEL_JOB, jobToken).sendBy(agentSession);
		} 
	}
	
	@Override
	public void resume(JobContext jobContext) {
		if (agentSession != null ) 
			new Message(MessageTypes.RESUME_JOB, jobContext.getJobToken()).sendBy(agentSession);
	}

	@Override
	public ShellSession openShell(IWebSocketConnection connection, JobContext jobContext) {
		if (agentSession != null) 
			return new RemoteSession(connection, agentSession, jobContext.getJobToken());
		else
			throw new ExplicitException("Shell not ready");
	}

	@Override
	public String getDockerExecutable() {
		return super.getDockerExecutable();
	}

	private static class TestRunnable implements ResourceRunnable {

		private static final long serialVersionUID = 1L;

		private final String jobToken;
		
		private final RemoteDockerExecutor jobExecutor;
		
		private final TestData testData;
		
		private final UUID dispatcherServerUUID;
		
		public TestRunnable(String jobToken, RemoteDockerExecutor jobExecutor, 
				TestData testData, UUID dispatcherServerUUID) {
			this.jobToken = jobToken;
			this.jobExecutor = jobExecutor;
			this.testData = testData;
			this.dispatcherServerUUID = dispatcherServerUUID;
		}
		
		@Override
		public void run(AgentInfo agentInfo) {
			jobExecutor.testLocal(jobToken, agentInfo, testData, dispatcherServerUUID);
		}
		
	}
	
}