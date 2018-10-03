/*
 * © Copyright 2013 EntIT Software LLC
 *  Certain versions of software and/or documents (“Material”) accessible here may contain branding from
 *  Hewlett-Packard Company (now HP Inc.) and Hewlett Packard Enterprise Company.  As of September 1, 2017,
 *  the Material is now offered by Micro Focus, a separately owned and operated company.  Any reference to the HP
 *  and Hewlett Packard Enterprise/HPE marks is historical in nature, and the HP and Hewlett Packard Enterprise/HPE
 *  marks are the property of their respective owners.
 * __________________________________________________________________
 * MIT License
 *
 * © Copyright 2012-2018 Micro Focus or one of its affiliates.
 *
 * The only warranties for products and services of Micro Focus and its affiliates
 * and licensors (“Micro Focus”) are set forth in the express warranty statements
 * accompanying such products and services. Nothing herein should be construed as
 * constituting an additional warranty. Micro Focus shall not be liable for technical
 * or editorial errors or omissions contained herein.
 * The information contained herein is subject to change without notice.
 * ___________________________________________________________________
 *
 */


/*
 * Create the PcGitSyncModel and the PcGitSyncClient and allows the connection between the job and PC
 * */

package com.microfocus.performancecenter.integration.pcgitsync;

import com.microfocus.performancecenter.integration.common.helpers.configuration.ConfigurationService;
import com.microfocus.performancecenter.integration.pcgitsync.helper.AbstractPcGitBuildStep;
import com.microfocus.performancecenter.integration.pcgitsync.helper.AbstractPcGitBuildStepDescriptor;
import com.microfocus.performancecenter.integration.pcgitsync.helper.RemoveScriptFromPC;
import com.microfocus.performancecenter.integration.common.helpers.utils.ModifiedFile;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.cloudbees.plugins.credentials.matchers.IdMatcher;
import com.cloudbees.plugins.credentials.common.StandardUsernameListBoxModel;

import hudson.*;
import hudson.model.*;
import hudson.model.queue.Tasks;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;

import lombok.Getter;
import lombok.Setter;

import com.microfocus.performancecenter.integration.pcgitsync.helper.UploadScriptMode;


import javax.annotation.Nonnull;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;

import org.kohsuke.stapler.QueryParameter;

import static com.microfocus.performancecenter.integration.common.helpers.utils.LogHelper.log;
import static com.microfocus.performancecenter.integration.common.helpers.utils.LogHelper.logStackTrace;

import com.microfocus.performancecenter.integration.common.helpers.services.ModifiedFiles;

public class PcGitSyncBuilder extends AbstractPcGitBuildStep<PcGitSyncBuilder.DescriptorImpl> {

    public static final String    ERROR           = "Error";

    private static transient UsernamePasswordCredentials usernamePCPasswordCredentials;
    private static transient UsernamePasswordCredentials usernamePCPasswordCredentialsForProxy;
    private static PrintStream logger;

    private final String description;
    private final String pcServerName;
    private final boolean httpsProtocol;
    private String credentialsId;
    private final String almDomain;
    private final String almProject;
    private final String serverAndPort;
    private final String proxyOutURL;
    private String credentialsProxyId;
    private final String subjectTestPlan;
    private final UploadScriptMode uploadScriptMode;
    private final RemoveScriptFromPC removeScriptFromPC;

    private PcGitSyncModel pcGitSyncModel;
    private String buildParameters;
    private boolean addDate = true;
    private static Run<?, ?> run;
    private File WorkspacePath;


    @DataBoundConstructor
    public PcGitSyncBuilder(
            String description,
            String pcServerName,
            boolean httpsProtocol,
            String credentialsId,
            String almDomain,
            String almProject,
            String serverAndPort,
            String proxyOutURL,
            String credentialsProxyId,
            String subjectTestPlan,
            UploadScriptMode uploadScriptMode,
            RemoveScriptFromPC removeScriptFromPC) {

        this.description = description;
        this.pcServerName = pcServerName;
        this.httpsProtocol = httpsProtocol;
        this.credentialsId = credentialsId;
        this.almDomain = almDomain;
        this.almProject = almProject;
        this.serverAndPort = serverAndPort;
        this.proxyOutURL = proxyOutURL;
        this.credentialsProxyId = credentialsProxyId;
        this.subjectTestPlan = subjectTestPlan;
        this.uploadScriptMode = uploadScriptMode;
        this.removeScriptFromPC = removeScriptFromPC;

        this.buildParameters = "";

        pcGitSyncModel =
                new PcGitSyncModel(
                        this.description.trim(),
                        this.pcServerName.trim(),
                        this.serverAndPort.trim(),
                        this.httpsProtocol,
                        this.credentialsId,
                        this.almDomain.trim(),
                        this.almProject.trim(),
                        this.proxyOutURL.trim(),
                        this.credentialsProxyId,
                        this.subjectTestPlan.trim(),
                        this.uploadScriptMode,
                        this.removeScriptFromPC,
                        buildParameters);
    }


    private String getPcUrl() {
        return (pcGitSyncModel.getHTTPSProtocol()) + "://" +  this.pcServerName + "/loadtest";
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException {
        if(build.getWorkspace() != null)
            WorkspacePath =  new File(build.getWorkspace().toURI());
        else
            WorkspacePath =  null;
        try { pcGitSyncModel.setBuildParameters(((AbstractBuild)build).getBuildVariables().toString()); } catch (Exception ex) { }
        if(build.getWorkspace() != null)
            perform(build, build.getWorkspace(), launcher, listener);
        else
            return false;
        return true;
    }

    public File getWorkspacePath(){
        return WorkspacePath;
    }

    private void setPcModelBuildParameters(AbstractBuild<?, ?> build) {
        String buildParameters = build.getBuildVariables().toString();
        if (!buildParameters.isEmpty())
            getPcGitSyncModel().setBuildParameters(buildParameters);
    }

    public String getAlmProject()
    {
        return getPcGitSyncModel().getAlmProject();
    }
    public String getAlmDomain()
    {
        return getPcGitSyncModel().getAlmDomain();
    }

    public PcGitSyncModel getPcGitSyncModel() {

        return pcGitSyncModel;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public static UsernamePasswordCredentials getUsernamePCPasswordCredentials() {
        return usernamePCPasswordCredentials;
    }

    public static UsernamePasswordCredentials getCredentialsId(String credentialsId)
    {
        if(credentialsId!=null && run != null )
            return getCredentialsById(credentialsId, run, logger, true);
        return null;
    }

    public static UsernamePasswordCredentials getCredentialsProxyId(String credentialsProxyId)
    {
        if(credentialsProxyId!=null && run != null )
            return getCredentialsById(credentialsProxyId, run, logger, false);
        return null;
    }

    public void setCredentialsId(String newCredentialsId)
    {
        credentialsId = newCredentialsId;
        //pcGitSyncModel = null;
        getPcGitSyncModel();
    }

    private static UsernamePasswordCredentials getCredentialsById(String credentialsId, Run<?, ?> run, PrintStream logger, boolean required) {
        if (StringUtils.isBlank(credentialsId)) {
            if (required)
                throw new NullPointerException("credentials is not configured.");
            else
                return null;
        }

        UsernamePasswordCredentials usernamePCPasswordCredentials = CredentialsProvider.findCredentialById(credentialsId,
                StandardUsernamePasswordCredentials.class,
                run,
                URIRequirementBuilder.create().build());

        if (usernamePCPasswordCredentials == null) {
            logger.println("Cannot find credentials with the credentialsId:" + credentialsId);
        }

        return usernamePCPasswordCredentials;
    }

    private String  simpleDateFormater()
    {
        try {
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat ("E yyyy MMM dd 'at' HH:mm:ss.SSS a zzz");
            String simpleDate = simpleDateFormat.format(new Date());
            if (simpleDate != null)
                return simpleDate;
            else
                return "";
        }
        catch (Exception ex) {
            return "";
        }
    }

    private static UsernamePasswordCredentials getCredentialsById(String credentialsId, Run<?, ?> run, PrintStream logger) {
        if (StringUtils.isBlank(credentialsId))
            return null;

        UsernamePasswordCredentials usernamePCPasswordCredentials = CredentialsProvider.findCredentialById(credentialsId,
                StandardUsernamePasswordCredentials.class,
                run,
                URIRequirementBuilder.create().build());

        if (usernamePCPasswordCredentials == null) {
            logger.println("Cannot find credentials with the credentialsId:" + credentialsId);
        }

        return usernamePCPasswordCredentials;
    }

    @Override
    public void perform(@Nonnull Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener) throws InterruptedException, IOException {

        run = build;
        logger = listener.getLogger();
        log(listener, "", addDate);

        try {
            String version = ConfigurationService.getVersion();
            if(!(version == null || version.equals("unknown")))
                log(listener, "plugin version is '%s'", addDate, version);
        } catch(IllegalStateException ex) {
            log(listener, "Error: IllegalStateException '%s'", addDate, ex.getMessage());
        }

        Set<ModifiedFile> modifiedFiles = getDescriptor()
                .getModifiedFiles()
                .getModifiedFilesSinceLastSuccess(listener, build, workspace.getRemote());

        usernamePCPasswordCredentials = getCredentialsId(credentialsId);
        usernamePCPasswordCredentialsForProxy = getCredentialsProxyId(credentialsProxyId);

        EnvVars env = build.getEnvironment(listener);
        String expandedSubject = env.expand(subjectTestPlan);

        PcGitSyncClient pcGitSyncClient = new PcGitSyncClient(
                listener,
                modifiedFiles,
                pcGitSyncModel,
                usernamePCPasswordCredentials,
                usernamePCPasswordCredentialsForProxy
        );
        Result result = Result.SUCCESS;
        try {
            Jenkins.getInstance().getInjector().injectMembers(pcGitSyncClient);

            result = workspace.act(pcGitSyncClient);
        } catch (Exception ex) {
            result = Result.FAILURE;
            log(listener, "Error: '%s'", addDate, ex.getMessage());
            logStackTrace(listener, ex);
        }

        build.setResult(result);

        log(listener, "", addDate);

    }

    private void provideStepResultStatus(Result resultStatus, Run<?, ?> build) {
        String runIdStr ="";
        logger.println(String.format("%s - Result Status%s: %s\n- - -",
                simpleDateFormater(),
                runIdStr,
                resultStatus.toString()));
        build.setResult(resultStatus);

    }

    //-----------------------------------------------------------------------------------------
    // This indicates to Jenkins that this is an implementation of an extension
    // point
    @Extension
    @Symbol("pcGitBuild")
//    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
    public static final class DescriptorImpl extends AbstractPcGitBuildStepDescriptor {


        @Getter
        @Setter(onMethod = @__(
                @Inject))
        private transient ModifiedFiles modifiedFiles;

        @Override
        public String getDisplayName() {

            return "Sync Performance scripts from GitHub to Performance Center";
        }

        public FormValidation doCheckPcServerName(@QueryParameter String value) {

            return validateString(value, "PC Server");
        }

        public FormValidation doCheckAlmUserName(@QueryParameter String value) {

            return validateString(value, "User name");
        }

        public FormValidation doCheckAlmDomain(@QueryParameter String value) {

            return validateString(value, "Domain");
        }

        public FormValidation doCheckSubjectTestPlan(@QueryParameter String value) {

            return validateString(value, "Test Plan folder");
        }

        public FormValidation doCheckAlmProject(@QueryParameter String value) {

            return validateString(value, "Project");
        }

        public FormValidation doCheckCredentialsId(@AncestorInPath Item project,
                                                   @QueryParameter String url,
                                                   @QueryParameter String value) {
            return CheckCredentialsId(project, url, value);
        }


        public FormValidation doCheckCredentialsProxyId(@AncestorInPath Item project,
                                                        @QueryParameter String url,
                                                        @QueryParameter String value) {
            return CheckCredentialsId(project, url, value);

        }

        private FormValidation CheckCredentialsId (Item project, String url, String value)
        {
            if (project == null || !project.hasPermission(Item.EXTENDED_READ)) {
                return FormValidation.ok();
            }

            value = Util.fixEmptyAndTrim(value);
            if (value == null) {
                return FormValidation.ok();
            }

            url = Util.fixEmptyAndTrim(url);
            if (url == null)
            // not set, can't check
            {
                return FormValidation.ok();
            }

            if (url.indexOf('$') >= 0)
            // set by variable, can't check
            {
                return FormValidation.ok();
            }

            for (ListBoxModel.Option o : CredentialsProvider.listCredentials(
                    StandardUsernamePasswordCredentials.class,
                    project,
                    project instanceof Queue.Task ? Tasks.getAuthenticationOf((Queue.Task) project) : ACL.SYSTEM,
                    URIRequirementBuilder.create().build(),
                    new IdMatcher(value))) {

                if (StringUtils.equals(value, o.value)) {
                    return FormValidation.ok();
                }
            }
            // no credentials available, can't check
            return FormValidation.warning("Cannot find any credentials with id " + value);
        }


        /**
         * @param limitIncluded
         *            if true, value must be higher than limit. if false, value must be equal to or
         *            higher than limit.
         */
        private FormValidation validateHigherThanInt(
                String value,
                String field,
                int limit,
                boolean limitIncluded) {
            FormValidation ret = FormValidation.ok();
            value = value.trim();
            String messagePrefix = field + " must be ";
            if (StringUtils.isBlank(value)) {
                ret = FormValidation.error(messagePrefix + "set");
            } else {
                try {
                    //regular expression: parameter (with brackets or not)
                    if (value.matches("^\\$\\{[\\w-. ]*}$|^\\$[\\w-.]*$"))
                        return ret;
                        //regular expression: number
                    else if (value.matches("[0-9]*$|")) {
                        if (limitIncluded && Integer.parseInt(value) <= limit)
                            ret = FormValidation.error(messagePrefix + "higher than " + limit);
                        else if (Integer.parseInt(value) < limit)
                            ret = FormValidation.error(messagePrefix + "at least " + limit);
                    }
                    else
                        ret = FormValidation.error(messagePrefix + "a whole number or a parameter, e.g.: 23, $TESTID or ${TEST_ID}.");
                } catch (Exception e) {
                    ret = FormValidation.error(messagePrefix + "a whole number or a parameter (e.g.: $TESTID or ${TestID})");
                }
            }

            return ret;

        }

        private FormValidation validateString(String value, String field) {
            FormValidation ret = FormValidation.ok();
            if (StringUtils.isBlank(value.trim())) {
                ret = FormValidation.error(field + " must be set");
            }

            return ret;
        }

        /**
         * To fill in the credentials drop down list which's field is 'credentialsId'.
         * This method's name works with tag <c:select/>.
         */
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item project,
                                                     @QueryParameter String credentialsId) {

            if (project == null || !project.hasPermission(Item.CONFIGURE)) {
                return new StandardUsernameListBoxModel().includeCurrentValue(credentialsId);
            }
            return new StandardUsernameListBoxModel()
                    .includeEmptyValue()
                    .includeAs(
                            project instanceof Queue.Task ? Tasks.getAuthenticationOf((Queue.Task) project) : ACL.SYSTEM,
                            project,
                            StandardUsernamePasswordCredentials.class,
                            URIRequirementBuilder.create().build())
                    .includeCurrentValue(credentialsId);
        }

        /**
         * To fill in the credentials drop down list which's field is 'credentialsProxyId'.
         * This method's name works with tag <c:select/>.
         */
        public ListBoxModel doFillCredentialsProxyIdItems(@AncestorInPath Item project,
                                                          @QueryParameter String credentialsId) {

            return doFillCredentialsIdItems(project, credentialsId);
        }

        public List<UploadScriptMode> getUploadScriptModes() {

            return PcGitSyncModel.getUploadScriptModes();
        }

        public List<RemoveScriptFromPC> getDeleteScripts() {

            return PcGitSyncModel.getDeleteScripts();
        }

    }

}