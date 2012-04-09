//
// ikvm-maven-plugin - generates C# DLL from Java code via IKVM
// http://github.com/samskivert/ikvm-maven-plugin/blob/master/LICENSE

package com.samskivert;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

/**
 * Goal which generates an IKVM dll containing all the jars on the classpath.
 *
 * @goal ikvm
 * @phase package
 */
public class IkvmMavenMojo extends AbstractMojo
{
    /**
     * Location of the IKVM installation.
     * @parameter expression="${ikvm.home}"
     * @required
     */
    public File ikvmHome;

    /**
     * The location of the Mono standard library DLLs.
     * @parameter expression="${stdlib.path}" default-value="/Developer/MonoTouch/usr/lib/mono/2.1"
     */
    public File stdlibPath;

    /**
     * Additional arguments to pass to IKVM.
     * @parameter
     */
    public List<String> ikvmArgs;

    /**
     * Additional DLLs (beyond mscorlib, System and System.Core) to reference. These can be
     * absoulte paths, or relative to {@ocde stdlibPath}.
     * @parameter
     */
    public List<String> dlls;

    /**
     * The local repository to use when resolving dependencies.
     * @parameter default-value="${localRepository}"
     */
    public ArtifactRepository localRepository;

    public void execute () throws MojoExecutionException {
        // sanity checks
        if (!stdlibPath.isDirectory()) {
            getLog().warn(stdlibPath + " does not exist. Skipping IKVM build.");
            return;
        }

        File ikvmc = new File(new File(ikvmHome, "bin"), "ikvmc.exe");
        if (!ikvmc.exists()) {
            throw new MojoExecutionException("Unable to find bin/ikmvc.exe in " + ikvmHome);
        }

        // resolve the (non-test) jar dependencies, all of which we'll include in our DLL
        List<File> depends = new ArrayList<File>();
        try {
            Set artifacts = _project.createArtifacts(_factory, null, null);
            // ArtifactFilter filter = ...
            ArtifactResolutionResult arr = _resolver.resolveTransitively(
                artifacts, _project.getArtifact(), _project.getManagedVersionMap(), localRepository,
                _project.getRemoteArtifactRepositories(), _metadataSource);
            for (Object art : arr.getArtifacts()) {
                Artifact artifact = (Artifact)art;
                if (!artifact.getScope().equals(Artifact.SCOPE_TEST)) {
                    depends.add(artifact.getFile());
                }
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to resolve dependencies.", e);
        }

        // create a command that executes mono on ikvmc.exe
        Commandline cli = new Commandline("mono");
        File ikvmcExe = new File(new File(ikvmHome, "bin"), "ikvmc.exe");
        cli.createArgument().setValue(ikvmcExe.getAbsolutePath());

        // add our standard args
        List<String> stdArgs = new ArrayList<String>();
        stdArgs.add("-nostdlib");
        stdArgs.add("-target:library");
        for (String arg : stdArgs) {
            cli.createArgument().setValue(arg);
        }

        // add our user defined args (making sure they don't duplicate stdargs)
        for (String arg : ikvmArgs) {
            if (stdArgs.contains(arg)) continue;
            if (arg.startsWith("-out:")) {
                getLog().warn("Don't specify -out:file directly. Set project.build.outputDirectory " +
                              "and project.build.finalName in your POM.");
                continue;
            }
            cli.createArgument().setValue(arg);
        }

        // add our output file
        String outname = _project.getBuild().getFinalName() + ".dll";
        File outfile = new File(new File(_project.getBuild().getDirectory()), outname);
        cli.createArgument().setValue("-out:" + outfile.getAbsolutePath());

        // set the MONO_PATH envvar
        cli.addEnvironment("MONO_PATH", stdlibPath.getAbsolutePath());

        // add our standard DLLs
        List<String> stdDlls = new ArrayList<String>();
        stdDlls.add("mscorlib.dll");
        stdDlls.add("System.dll");
        stdDlls.add("System.Core.dll");
        for (String dll : stdDlls) {
            cli.createArgument().setValue("-r:" + new File(stdlibPath, dll).getAbsolutePath());
        }

        // add our DLLs
        for (String dll : dlls) {
            if (stdDlls.contains(dll)) continue;
            if (new File(dll).isAbsolute()) {
                cli.createArgument().setValue("-r:" + dll);
            } else {
                cli.createArgument().setValue("-r:" + new File(stdlibPath, dll).getAbsolutePath());
            }
        }

        // add our jar files
        for (File depend : depends) {
            cli.createArgument().setValue(depend.getAbsolutePath());
        }

        // log our full command for great debuggery
        getLog().debug("CMD: " + cli);

        CommandLineUtils.StringStreamConsumer stdoutC = new CommandLineUtils.StringStreamConsumer();
        CommandLineUtils.StringStreamConsumer stderrC = new CommandLineUtils.StringStreamConsumer();
        try {
            int rv = CommandLineUtils.executeCommandLine(cli, null, stdoutC, stderrC);

            String stdout = stdoutC.getOutput();
            if (stdout != null && stdout.length() > 0) getLog().info(stdout);

            String stderr = stderrC.getOutput();
            if (stderr != null && stderr.length() > 0) getLog().warn(stderr);

            if (rv != 0) {
                throw new MojoExecutionException("ikvmc.exe failed; see above output.");
            }

        } catch (CommandLineException clie) {
            throw new MojoExecutionException("Executing ikvmc.exe failed", clie);
        }
    }

    /** @parameter default-value="${project}" */
    private MavenProject _project;

    /** @component */
    private ArtifactResolver _resolver;

    /** @component */
    private ArtifactMetadataSource _metadataSource;

    /** @component */
    private ArtifactFactory _factory;
}
