package io.onedev.server.plugin.report.markdown;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.apache.wicket.protocol.http.WebApplication;

import com.google.common.collect.Sets;

import io.onedev.commons.loader.AbstractPluginModule;
import io.onedev.commons.loader.ImplementationProvider;
import io.onedev.commons.utils.LockUtils;
import io.onedev.server.OneDev;
import io.onedev.server.buildspec.step.PublishReportStep;
import io.onedev.server.cluster.ClusterTask;
import io.onedev.server.entitymanager.ProjectManager;
import io.onedev.server.model.Build;
import io.onedev.server.model.PullRequest;
import io.onedev.server.security.SecurityUtils;
import io.onedev.server.web.WebApplicationConfigurator;
import io.onedev.server.web.mapper.BasePageMapper;
import io.onedev.server.web.page.project.builds.detail.BuildTab;
import io.onedev.server.web.page.project.builds.detail.BuildTabContribution;
import io.onedev.server.web.page.project.pullrequests.detail.PullRequestSummaryContribution;
import io.onedev.server.web.page.project.pullrequests.detail.PullRequestSummaryPart;

/**
 * NOTE: Do not forget to rename moduleClass property defined in the pom if you've renamed this class.
 *
 */
public class MarkdownReportModule extends AbstractPluginModule {

	@Override
	protected void configure() {
		super.configure();
		
		contribute(ImplementationProvider.class, new ImplementationProvider() {

			@Override
			public Class<?> getAbstractClass() {
				return PublishReportStep.class;
			}
			
			@Override
			public Collection<Class<?>> getImplementations() {
				return Sets.newHashSet(PublishMarkdownReportStep.class, PublishPullRequestMarkdownReportStep.class);
			}
			
		});
		
		contribute(BuildTabContribution.class, new BuildTabContribution() {
			
			@Override
			public List<BuildTab> getTabs(Build build) {
				ProjectManager projectManager = OneDev.getInstance(ProjectManager.class);
				Long projectId = build.getProject().getId();
				Long buildNumber = build.getNumber();
				
				return projectManager.runOnProjectServer(projectId, new GetBuildTabs(projectId, buildNumber)).stream()
						.filter(it->SecurityUtils.canAccessReport(build, it.getTitle()))
						.collect(Collectors.toList());
			}
			
			@Override
			public int getOrder() {
				return 300;
			}
			
		});
		
		contribute(PullRequestSummaryContribution.class, new PullRequestSummaryContribution() {

			@Override
			public List<PullRequestSummaryPart> getParts(PullRequest request) {
				List<PullRequestSummaryPart> parts = new ArrayList<>();
				for (Build build: request.getCurrentBuilds()) {
					parts.addAll(LockUtils.read(PublishPullRequestMarkdownReportStep.getReportLockKey(build), new Callable<List<PullRequestSummaryPart>>() {

						@Override
						public List<PullRequestSummaryPart> call() throws Exception {
							List<PullRequestSummaryPart> parts = new ArrayList<>();
							File categoryDir = new File(build.getDir(), PublishPullRequestMarkdownReportStep.CATEGORY);
							if (categoryDir.exists()) {
								for (File reportDir: categoryDir.listFiles()) {
									if (SecurityUtils.canAccessReport(build, reportDir.getName()))
										parts.add(new PullRequestSummaryMarkdownPart(reportDir));
								}
							}
							return parts;
						}
						
					}));
				}
				return parts;
			}

			@Override
			public int getOrder() {
				return 100;
			}
			
		});
		
		contribute(WebApplicationConfigurator.class, new WebApplicationConfigurator() {
			
			@Override
			public void configure(WebApplication application) {
				application.mount(new BasePageMapper(
						"projects/${project}/builds/${build}/markdown/${report}", 
						MarkdownReportPage.class));
			}
			
		});	
		
	}

	private static class GetBuildTabs implements ClusterTask<List<BuildTab>> {

		private static final long serialVersionUID = 1L;
		
		private final Long projectId;
		
		private final Long buildNumber;
		
		public GetBuildTabs(Long projectId, Long buildNumber) {
			this.projectId = projectId;
			this.buildNumber = buildNumber;
		}

		@Override
		public List<BuildTab> call() throws Exception {
			return LockUtils.read(PublishMarkdownReportStep.getReportLockName(projectId, buildNumber), new Callable<List<BuildTab>>() {

				@Override
				public List<BuildTab> call() throws Exception {
					List<BuildTab> tabs = new ArrayList<>();
					File categoryDir = new File(Build.getDir(projectId, buildNumber), PublishPullRequestMarkdownReportStep.CATEGORY);
					if (categoryDir.exists()) {
						for (File reportDir: categoryDir.listFiles()) 
							tabs.add(new MarkdownReportTab(reportDir.getName()));
					}
					Collections.sort(tabs, new Comparator<BuildTab>() {

						@Override
						public int compare(BuildTab o1, BuildTab o2) {
							return o1.getTitle().compareTo(o1.getTitle());
						}
						
					});
					return tabs;
				}
				
			});
		}
		
	}
	
}
