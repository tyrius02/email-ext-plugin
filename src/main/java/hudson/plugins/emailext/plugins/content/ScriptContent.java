package hudson.plugins.emailext.plugins.content;

import groovy.lang.Binding;
import groovy.lang.GroovyRuntimeException;
import groovy.lang.GroovyShell;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Hudson;
import hudson.plugins.emailext.EmailType;
import hudson.plugins.emailext.ExtendedEmailPublisher;
import hudson.plugins.emailext.ScriptSandbox;
import hudson.plugins.emailext.plugins.EmailContent;

import jenkins.model.Jenkins;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.script.ScriptException;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.kohsuke.groovy.sandbox.SandboxTransformer;

public class ScriptContent implements EmailContent {

    private static final Logger LOGGER = Logger.getLogger(ScriptContent.class.getName());
    public static final String SCRIPT_NAME_ARG = "script";
    public static final String SCRIPT_TEMPLATE_ARG = "template";
    public static final String SCRIPT_INIT_ARG = "init";
    private static final String DEFAULT_SCRIPT_NAME = "email-ext.groovy";
    private static final String DEFAULT_TEMPLATE_NAME = "groovy-html.template";
    private static final boolean DEFAULT_INIT_VALUE = true;
    private static final String EMAIL_TEMPLATES_DIRECTORY = "email-templates";

    public String getToken() {
        return "SCRIPT";
    }

    public String getHelpText() {
        StringBuilder helpText = new StringBuilder("Custom message content generated from a groovy script. "
                + "Custom scripts should be placed in "
                + "$JENKINS_HOME/" + EMAIL_TEMPLATES_DIRECTORY + ". When using custom scripts, "
                + "the script filename should be used for "
                + "the \"" + SCRIPT_NAME_ARG + "\" argument.\n"
                + "templates and other items may be loaded using the\n"
                + "host.readFile(String fileName) function\n"
                + "the function will look in the resources for\n"
                + "the email-ext plugin first, and then in the $JENKINS_HOME/" + EMAIL_TEMPLATES_DIRECTORY + "\n"
                + "directory. No other directories will be searched.\n"
                + "<ul>\n"
                + "<li><i>" + SCRIPT_NAME_ARG + "</i> - the script name.<br>\n"
                + "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Defaults to \"" + DEFAULT_SCRIPT_NAME + "\".</li>\n"
                + "<li><i>" + SCRIPT_TEMPLATE_ARG + "</i> - the template filename.<br>\n"
                + "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Defaults to \"" + DEFAULT_TEMPLATE_NAME + "\"</li>\n"
                + "<li><i>" + SCRIPT_INIT_ARG + "</i> - true to run the language's init script.<br>\n"
                + "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Defaults to " + DEFAULT_INIT_VALUE + "</li>\n"
                + "</ul>\n");
        return helpText.toString();
    }

    public List<String> getArguments() {
        List<String> args = new ArrayList<String>();
        args.add(SCRIPT_NAME_ARG);
        args.add(SCRIPT_TEMPLATE_ARG);
        args.add(SCRIPT_INIT_ARG);
        return args;
    }

    public <P extends AbstractProject<P, B>, B extends AbstractBuild<P, B>> String getContent(AbstractBuild<P, B> build, ExtendedEmailPublisher publisher, EmailType type, Map<String, ?> args)
            throws IOException, InterruptedException {

        InputStream inputStream = null;
        InputStream templateStream = null;
        String scriptName = Args.get(args, SCRIPT_NAME_ARG, DEFAULT_SCRIPT_NAME);
        String templateName = Args.get(args, SCRIPT_TEMPLATE_ARG, DEFAULT_TEMPLATE_NAME);
        boolean runInit = Args.get(args, SCRIPT_INIT_ARG, DEFAULT_INIT_VALUE);

        try {
            inputStream = getFileInputStream(scriptName);
            // sanity check on template as well
            templateStream = getFileInputStream(templateName);
            IOUtils.closeQuietly(templateStream);
            return renderContent(build, publisher, inputStream, scriptName, templateName, runInit);
        } catch (FileNotFoundException e) {
            String missingScriptError = generateMissingFile(scriptName, templateName);
            LOGGER.log(Level.SEVERE, missingScriptError);
            return missingScriptError;
        } catch (ScriptException e) {
            LOGGER.log(Level.SEVERE, null, e);
            return "Exception: " + e.getMessage();
        } catch(GroovyRuntimeException e) {
            return "Error in script or template: " + e.toString();
        } finally {
            IOUtils.closeQuietly(inputStream);
        }
    }

    private String generateMissingFile(String script, String template) {
        return "Script [" + script + "] or template [" + template + "] was not found in $JENKINS_HOME/" + EMAIL_TEMPLATES_DIRECTORY + ".";
    }

    /**
     * Try to get the script from the classpath first before trying the file
     * system.
     *
     * @param scriptName
     * @return
     * @throws java.io.FileNotFoundException
     */
    private InputStream getFileInputStream(String fileName)
            throws FileNotFoundException {
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream("hudson/plugins/emailext/templates/" + fileName);
        if (inputStream == null) {
            final File scriptsFolder = new File(Hudson.getInstance().getRootDir(), EMAIL_TEMPLATES_DIRECTORY);
            final File scriptFile = new File(scriptsFolder, fileName);
            inputStream = new FileInputStream(scriptFile);
        }
        return inputStream;
    }

    private String renderContent(AbstractBuild<?, ?> build, ExtendedEmailPublisher publisher,
            InputStream inputStream, String scriptName, String templateName, boolean runInit)
            throws ScriptException, IOException {
        String rendered = "";
        GroovyShell engine = createEngine(scriptName, templateName, runInit,
                new ScriptContentBuildWrapper(build), build, publisher);
        if (engine != null) {
            try {
                Object res = engine.evaluate(new InputStreamReader(inputStream));
                if (res != null) {
                    rendered = res.toString();
                } 
            } finally {
                IOUtils.closeQuietly(inputStream);
            }
        }
        return rendered;
    }

    public String readFile(String fileName)
            throws FileNotFoundException, IOException, UnsupportedEncodingException {
        String result = "";
        InputStream inputStream = getFileInputStream(fileName);
        if (inputStream != null) {
            Writer writer = new StringWriter();
            char[] buffer = new char[2048];
            try {
                Reader reader = new BufferedReader(
                        new InputStreamReader(inputStream, "UTF-8"));
                int n;
                while ((n = reader.read(buffer)) != -1) {
                    writer.write(buffer, 0, n);
                }
                result = writer.toString();
            } finally {
                IOUtils.closeQuietly(inputStream);
            }
        }
        return result;
    }

    private GroovyShell createEngine(String scriptName, String templateName, boolean runInit,
            Object it, AbstractBuild<?, ?> build, ExtendedEmailPublisher publisher)
            throws FileNotFoundException, IOException {

        ClassLoader cl = Jenkins.getInstance().getPluginManager().uberClassLoader;
        ScriptSandbox sandbox = null;
        CompilerConfiguration cc = new CompilerConfiguration();
        cc.addCompilationCustomizers(new ImportCustomizer().addStarImports(
                "jenkins",
                "jenkins.model",
                "hudson",
                "hudson.model"));

        if (ExtendedEmailPublisher.DESCRIPTOR.isSecurityEnabled()) {
            cc.addCompilationCustomizers(new SandboxTransformer());
            sandbox = new ScriptSandbox();
        }

        Binding binding = new Binding();
        binding.setVariable("build", build);
        binding.setVariable("it", it);
        binding.setVariable("project", build.getParent());
        binding.setVariable("rooturl", ExtendedEmailPublisher.DESCRIPTOR.getHudsonUrl());
        binding.setVariable("host", this);
        binding.setVariable("publisher", publisher);
        binding.setVariable("template", templateName);

        GroovyShell shell = new GroovyShell(cl, binding, cc);
        StringWriter out = new StringWriter();
        PrintWriter pw = new PrintWriter(out);

        if (sandbox != null) {
            sandbox.register();
        }

        if (runInit) {
            InputStream initFile = null;
            try {
                initFile = getFileInputStream("groovy/init.groovy");
                if (initFile != null) {
                    shell.evaluate(new InputStreamReader(initFile));
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Exception on init file: {0}", e.toString());
            } finally {
                IOUtils.closeQuietly(initFile);
            }
        }

        return shell;
    }

    public boolean hasNestedContent() {
        return false;
    }

    private String join(List<String> s, String delimiter) {
        if (s.isEmpty()) {
            return "";
        }
        Iterator<String> iter = s.iterator();
        StringBuilder builder = new StringBuilder(iter.next());
        while (iter.hasNext()) {
            builder.append(delimiter);
            builder.append(iter.next());
        }
        return builder.toString();
    }
}
