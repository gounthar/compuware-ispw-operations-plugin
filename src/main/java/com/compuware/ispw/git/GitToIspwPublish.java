/**
*  Copyright (c) 2020 Compuware Corporation. All rights reserved.
* (c) Copyright 2020,2021-2022 BMC Software, Inc.
*/
package com.compuware.ispw.git;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import com.compuware.ispw.restapi.Constants;
import com.compuware.ispw.restapi.util.RestApiUtils;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Item;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.UserRemoteConfig;
import hudson.scm.NullSCM;
import hudson.scm.SCM;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;

/**
 * GIT to ISPW publisher
 * 
 * @author Sam Zhou
 *
 */
public class GitToIspwPublish extends Builder implements IGitToIspwPublish
{
	// Git related
	String gitRepoUrl = StringUtils.EMPTY;
	String gitCredentialsId = StringUtils.EMPTY;

	// ISPW related
	private String connectionId = DescriptorImpl.connectionId;
	private String credentialsId = DescriptorImpl.credentialsId;
	private String runtimeConfig = DescriptorImpl.runtimeConfig;
	private String stream = DescriptorImpl.stream;
	private String app = DescriptorImpl.app;
	private String subAppl = DescriptorImpl.subAppl;
	private String ispwConfigPath = DescriptorImpl.ispwConfigPath;

	// Branch mapping
	private String branchMapping = DescriptorImpl.branchMapping;

	@DataBoundConstructor
	public GitToIspwPublish()
	{
	}

	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
			throws InterruptedException, IOException
	{

		PrintStream logger = listener.getLogger();

		AbstractProject<?, ?> project = build.getProject();
		SCM scm = project.getScm();
		if (scm instanceof GitSCM)
		{
			GitSCM gitSCM = (GitSCM) scm;
			List<UserRemoteConfig> userRemoteConfigs = gitSCM.getUserRemoteConfigs();
			gitRepoUrl = userRemoteConfigs.get(0).getUrl();
			gitCredentialsId = userRemoteConfigs.get(0).getCredentialsId();
		}
		else
		{
			if (scm instanceof NullSCM)
			{
				throw new AbortException(
						"Jenkins Git Plugin SCM is required along with selecting the Git option in the Source Code Management section and providing the Git repository URL and credentials."); //$NON-NLS-1$
			}
			else
			{
				throw new AbortException(
						"The Git option must be selected in the Jenkins project Source Code Management section along with providing the Git repository URL and credentials. The Source Code Management section selection type is " //$NON-NLS-1$
								+ scm.getType());
			}

		}

		EnvVars envVars = build.getEnvironment(listener);
		GitToIspwUtils.trimEnvironmentVariables(envVars);

		FilePath buildParmPath = GitToIspwUtils.getFilePathInVirtualWorkspace(envVars, Constants.BUILD_PARAM_FILE_NAME);
    	
		if (buildParmPath.exists()) {
    		logger.println("Remove the old build parm files." + buildParmPath.getName()); //$NON-NLS-1$
    		buildParmPath.delete();
    	}

		Map<String, RefMap> map = GitToIspwUtils.parse(branchMapping);
		logger.println("branch mapping = " + map);
		
		String refId = envVars.get(GitToIspwConstants.VAR_REF_ID, null);
		logger.println("branch name (refId) = " + refId);
		
		BranchPatternMatcher matcher = new BranchPatternMatcher(map, logger);
		RefMap refMap = matcher.match(refId);
		RestApiUtils.assertNotNull(logger, refMap,
				"Cannot find a branch pattern that matches the branch %s. Please adjust your branch mapping.", refId);
			
		//Test to determine if there was a from hash which may not be the case for a new branch first build.
		//If "from hash" is all zeros and "to hash" is good, then do the special case of setting fromHash to -2 which
		//will handle the Diffs differently in the CLI.
		String fromHash = envVars.get(GitToIspwConstants.VAR_FROM_HASH, null);
		String toHash = envVars.get(GitToIspwConstants.VAR_TO_HASH, null);
		if (fromHash != null && toHash != null)
		{
			toHash = toHash.replace("0", StringUtils.EMPTY);
			fromHash = fromHash.replace("0", StringUtils.EMPTY);
			if (fromHash.isEmpty() && !toHash.isEmpty())
			{
				envVars.put(GitToIspwConstants.VAR_FROM_HASH, "-2");
			}
		}
		
		// Sync to ISPW
		if (GitToIspwUtils.callCli(launcher, build, logger, envVars, refMap, this))
		{
			return true;
		}
		else
		{
			logger.println("An error occurred while synchronizing source to ISPW");
			return false;
		}
	}

	@Extension
	public static final class DescriptorImpl extends BuildStepDescriptor<Builder>
	{
		// ISPW related
		public static final String connectionId = StringUtils.EMPTY;
		public static final String credentialsId = StringUtils.EMPTY;
		public static final String runtimeConfig = StringUtils.EMPTY;
		public static final String stream = StringUtils.EMPTY;
		public static final String app = StringUtils.EMPTY;
		public static final String subAppl = StringUtils.EMPTY;
		public static final String ispwConfigPath = StringUtils.EMPTY;

		// Branch mapping
		public static final String branchMapping = GitToIspwConstants.BRANCH_MAPPING_DEFAULT;

		public static final String containerDesc = StringUtils.EMPTY;
		public static final String containerPref = StringUtils.EMPTY;

		public DescriptorImpl()
		{
			load();
		}

		@Override
		public String getDisplayName()
		{
			return "Git to ISPW Integration";
		}

		@SuppressWarnings("rawtypes")
		@Override
		public boolean isApplicable(Class<? extends AbstractProject> aClass)
		{
			return true;
		}

		// GIT
		public ListBoxModel doFillGitCredentialsIdItems(@AncestorInPath Jenkins context,
				@QueryParameter String gitCredentialsId, @AncestorInPath Item project)
		{
			if(project == null) {
				//Checking Permission for admin user
				Jenkins.get().checkPermission(Jenkins.ADMINISTER);
			}
			else {
				project.checkPermission(Item.CONFIGURE);				
			}
			return GitToIspwUtils.buildStandardCredentialsIdItems(context, gitCredentialsId, project);
		}

		// ISPW
		public ListBoxModel doFillConnectionIdItems(@AncestorInPath Jenkins context, @QueryParameter String connectionId,
				@AncestorInPath Item project)
		{
			if(project == null) {
				//Checking Permission for admin user
				Jenkins.get().checkPermission(Jenkins.ADMINISTER);
			}
			else {
				project.checkPermission(Item.CONFIGURE);				
			}
			return RestApiUtils.buildConnectionIdItems(context, connectionId, project);
		}

		public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Jenkins context, @QueryParameter String credentialsId,
				@AncestorInPath Item project)
		{
			if(project == null) {
				//Checking Permission for admin user
				Jenkins.get().checkPermission(Jenkins.ADMINISTER);
			}
			else {
				project.checkPermission(Item.CONFIGURE);				
			}
			return GitToIspwUtils.buildStandardCredentialsIdItems(context, credentialsId, project);
		}

	}

	@Initializer(before = InitMilestone.PLUGINS_STARTED)
	public static void xStreamCompatibility()
	{
	}

	/**
	 * @return the gitRepoUrl
	 */
	public String getGitRepoUrl()
	{
		return gitRepoUrl;
	}

	/**
	 * @return the gitCredentialsId
	 */
	public String getGitCredentialsId()
	{
		return gitCredentialsId;
	}

	/**
	 * @return the connectionId
	 */
	public String getConnectionId()
	{
		return connectionId;
	}

	/**
	 * @param connectionId
	 *            the connectionId to set
	 */
	@DataBoundSetter
	public void setConnectionId(String connectionId)
	{
		this.connectionId = connectionId;
	}

	/**
	 * @return the credentialsId
	 */
	public String getCredentialsId()
	{
		return credentialsId;
	}

	/**
	 * @param credentialsId
	 *            the credentialsId to set
	 */
	@DataBoundSetter
	public void setCredentialsId(String credentialsId)
	{
		this.credentialsId = credentialsId;
	}

	/**
	 * @return the runtimeConfig
	 */
	public String getRuntimeConfig()
	{
		return runtimeConfig;
	}

	/**
	 * @param runtimeConfig
	 *            the runtimeConfig to set
	 */
	@DataBoundSetter
	public void setRuntimeConfig(String runtimeConfig)
	{
		this.runtimeConfig = runtimeConfig;
	}

	/**
	 * @return the stream
	 */
	public String getStream()
	{
		return stream;
	}

	/**
	 * @param stream
	 *            the stream to set
	 */
	@DataBoundSetter
	public void setStream(String stream)
	{
		this.stream = stream;
	}

	/**
	 * @return the app
	 */
	public String getApp()
	{
		return app;
	}

	/**
	 * @param app
	 *            the app to set
	 */
	@DataBoundSetter
	public void setApp(String app)
	{
		this.app = app;
	}	

	/**
	 * @return the subAppl
	 */
	public String getSubAppl()
	{
		return subAppl;
	}

	/**
	 * @param subAppl the subAppl to set
	 */
	@DataBoundSetter
	public void setSubAppl(String subAppl)
	{
		this.subAppl = subAppl;
	}

	/**
	 * @return the branchMapping
	 */
	public String getBranchMapping()
	{
		return branchMapping;
	}

	/**
	 * @param branchMapping
	 *            the branchMapping to set
	 */
	@DataBoundSetter
	public void setBranchMapping(String branchMapping)
	{
		this.branchMapping = branchMapping;
	}

	/**
	 * @return the ispwConfigPath
	 */
	public String getIspwConfigPath()
	{
		return ispwConfigPath;
	}

	/**
	 * @param ispwConfigPath the ispwConfigPath to set
	 */
	@DataBoundSetter
	public void setIspwConfigPath(String ispwConfigPath)
	{
		this.ispwConfigPath = ispwConfigPath;
	}
}