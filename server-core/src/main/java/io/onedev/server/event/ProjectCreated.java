package io.onedev.server.event;

import io.onedev.server.model.Project;
import io.onedev.server.security.SecurityUtils;

public class ProjectCreated extends ProjectEvent {
	
	private static final long serialVersionUID = 1L;

	public ProjectCreated(Project project) {
		super(SecurityUtils.getUser(), project.getCreateDate(), project);
	}

	@Override
	public String getActivity() {
		return "created";
	}

}
