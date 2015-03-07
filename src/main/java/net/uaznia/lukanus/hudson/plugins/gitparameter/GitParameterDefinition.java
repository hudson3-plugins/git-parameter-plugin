package net.uaznia.lukanus.hudson.plugins.gitparameter;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.AbstractProject;
import hudson.model.Hudson;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.plugins.git.GitAPI;
import hudson.plugins.git.GitException;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.GitTool;
import hudson.plugins.git.IGitAPI;
import hudson.plugins.git.Revision;
import hudson.remoting.Future;
import hudson.remoting.VirtualChannel;
import hudson.scm.SCM;
import hudson.util.StreamTaskListener;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RemoteConfig;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

public class GitParameterDefinition extends ParameterDefinition implements
        Comparable<GitParameterDefinition> {

    private static final long serialVersionUID = 9157832967140868122L;

    public static final String PARAMETER_TYPE_TAG = "PT_TAG";
    public static final String PARAMETER_TYPE_REVISION = "PT_REVISION";

    private final UUID uuid;

    @Extension
    public static class DescriptorImpl extends ParameterDescriptor {

        @Override
        public String getDisplayName() {
            return "Git Parameter";
        }
    }

    private String type;
    private String branch;

    private String errorMessage;
    private String defaultValue;

    public boolean hasError = false;

    @DataBoundConstructor
    public GitParameterDefinition(String name, String type,
            String defaultValue, String description, String branch) {
        super(name, description);
        this.type = type;
        this.defaultValue = defaultValue;
        this.branch = branch;

        this.uuid = UUID.randomUUID();
    }

    @Override
    public ParameterValue createValue(StaplerRequest request) {
        String value[] = request.getParameterValues(getName());
        if (value == null) {
            return getDefaultParameterValue();
        }
        return null;
    }

    @Override
    public ParameterValue createValue(StaplerRequest request, JSONObject jO) {
        Object value = jO.get("value");
        String strValue = "";
        if (value instanceof String) {
            strValue = (String) value;
        } else if (value instanceof JSONArray) {
            JSONArray jsonValues = (JSONArray) value;
            for (int i = 0; i < jsonValues.size(); i++) {
                strValue += jsonValues.getString(i);
                if (i < jsonValues.size() - 1) {
                    strValue += ",";
                }
            }
        }

        if ("".equals(strValue)) {
            strValue = defaultValue;
        }

        GitParameterValue gitParameterValue = new GitParameterValue(jO.getString("name"), strValue);
        return gitParameterValue;
    }

    @Override
    public ParameterValue getDefaultParameterValue() {
        String defValue = getDefaultValue();
        if (!StringUtils.isBlank(defValue)) {
            return new GitParameterValue(getName(), defValue);
        }
        return super.getDefaultParameterValue();
    }

    @Override
    public String getType() {
        return type;
    }

    public void setType(String type) {
        if (type.equals(PARAMETER_TYPE_TAG)
                || type.equals(PARAMETER_TYPE_REVISION)) {
            this.type = type;
        } else {
            this.errorMessage = "wrongType";

        }
    }

    public String getBranch() {
        return this.branch;
    }

    public void setBranch(String nameOfBranch) {
        this.branch = nameOfBranch;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public AbstractProject<?, ?> getParentProject() {
        AbstractProject<?, ?> context = null;
        List<AbstractProject> jobs = Hudson.getInstance().getItems(AbstractProject.class);

        for (AbstractProject<?, ?> project : jobs) {
            ParametersDefinitionProperty property = (ParametersDefinitionProperty) project.getProperty(ParametersDefinitionProperty.class);

            if (property != null) {
                List<ParameterDefinition> parameterDefinitions = property.getParameterDefinitions();

                if (parameterDefinitions != null) {
                    for (ParameterDefinition pd : parameterDefinitions) {

                        if (pd instanceof GitParameterDefinition
                                && ((GitParameterDefinition) pd).compareTo(this) == 0) {

                            context = project;
                            break;
                        }
                    }
                }
            }
        }

        return context;
    }

    @Override
    public int compareTo(GitParameterDefinition pd) {
        if (pd.uuid.equals(uuid)) {
            return 0;
        }

        return -1;
    }

    private String getGitExe() {
        String defaultGitExe = File.separatorChar != '/' ? "git.exe" : "git";

        hudson.plugins.git.GitTool.DescriptorImpl descriptor = (hudson.plugins.git.GitTool.DescriptorImpl) Hudson.getInstance().getDescriptor(GitTool.class);
        GitTool[] installations = descriptor.getInstallations();

        for (GitTool gt : installations) {
            if (gt.getGitExe() != null) {
                defaultGitExe = gt.getGitExe();
                break;
            }
        }
        return defaultGitExe;
    }

    public Map<String, String> generateContents(String contenttype) throws IOException, InterruptedException, ExecutionException {
        
        Map<String, String> revTagMap = new LinkedHashMap<String, String>();

        hasError = false;

        final AbstractProject<?, ?> project = getParentProject();

        if (project.getSomeBuildWithWorkspace() == null) {
            this.errorMessage = "No workspace yet!. Perform at least one build to create workspace.";
            hasError = true;
            revTagMap.put(errorMessage, errorMessage);
            return revTagMap;
        }

        SCM scm = project.getScm();

        if (!(scm instanceof GitSCM)) {
            this.errorMessage = "No Git configured in this job.";
            hasError = true;
            revTagMap.put(errorMessage, errorMessage);
            return revTagMap;
        }

        GitSCM git = (GitSCM) scm;

        final String gitExe = getGitExe();

        for (final RemoteConfig repository : git.getRepositories()) {

            FilePath workingDirectory = project.getSomeBuildWithWorkspace().getWorkspace();

            Future<Map> future = workingDirectory.actAsync(new FilePath.FileCallable<Map>() {
                private static final long serialVersionUID = 1L;

                public Map invoke(File workspace,
                        VirtualChannel channel) throws IOException {

                    IGitAPI newgit = new GitAPI(gitExe, new FilePath(workspace), new StreamTaskListener(System.out), new EnvVars());

                    Map<String, String> revTagMap = new LinkedHashMap<String, String>();;

                    try {
                        newgit.fetch(repository);
                    } catch (GitException ge) {
                        // fetch fails when workspace is empty, run clone
                        newgit.clone(repository);
                    }

                    if (type.equalsIgnoreCase(PARAMETER_TYPE_REVISION)) {

                        List<ObjectId> oid;

                        if (branch != null && !branch.isEmpty()) {
                            oid = newgit.revListBranch(branch);
                        } else {
                            oid = newgit.revListAll();
                        }

                        for (ObjectId noid : oid) {
                            Revision r = new Revision(noid);
                            List<String> details = newgit.showRevision(r);

                            String author = "";

                            for (String detail : details) {
                                if (detail.startsWith("author")) {
                                    author = detail;
                                    break;
                                }
                            }

                            String[] authorDate = author.split(">");
                            String authorInfo = authorDate[0].replaceFirst("author ", "").replaceFirst("committer ", "") + ">";
                            String goodDate = "";
                            try {
                                String totmp = authorDate[1].trim();
                                if (totmp.contains("+")) {
                                    totmp = totmp.split("\\+")[0].trim();
                                } else {
                                    totmp = totmp.split("\\-")[0].trim();
                                }

                                long timestamp = Long.parseLong(totmp, 10) * 1000;
                                Date date = new Date();
                                date.setTime(timestamp);

                                goodDate = new SimpleDateFormat("yyyy-MM-dd hh:mm").format(date);

                            } catch (Exception e) {
                                e.toString();
                            }
                            revTagMap.put(r.getSha1String(), r.getSha1String() + " " + authorInfo + " " + goodDate);
                        }
                    } else if (type.equalsIgnoreCase(PARAMETER_TYPE_TAG)) {

                        // Set<String> tagNameList = newgit.getTagNames("*");
                        for (String tagName : newgit.getTagNames("*")) {
                            revTagMap.put(tagName, tagName);
                        }
                    }
                    return revTagMap;
                }
            });
            revTagMap.putAll(future.get());
        }
        return revTagMap;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Map<String, String> getRevisionMap() throws IOException, InterruptedException, ExecutionException {
        return generateContents(PARAMETER_TYPE_REVISION);
    }

    public Map<String, String> getTagMap() throws IOException, InterruptedException, ExecutionException {
        return generateContents(PARAMETER_TYPE_TAG);
    }

}
