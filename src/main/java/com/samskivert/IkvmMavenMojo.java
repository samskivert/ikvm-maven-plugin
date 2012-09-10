//
// ikvm-maven-plugin - generates C# DLL from Java code via IKVM
// http://github.com/samskivert/ikvm-maven-plugin/blob/master/LICENSE

package com.samskivert;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.codehaus.plexus.util.Expand;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

/**
 * Goal which generates an IKVM dll containing all the jars on the classpath.
 *
 * @requiresDependencyResolution compile
 * @goal ikvm
 * @phase package
 */
public class IkvmMavenMojo extends AbstractMojo
{
    /**
     * Location of the IKVM installation.
     * @parameter expression="${ikvm.path}"
     */
    public File ikvmPath;

    /**
     * The location of the Mono standard library DLLs.
     * @parameter expression="${mono.path}" default-value="/Developer/MonoTouch/usr/lib/mono/2.1"
     */
    public File monoPath;

    /**
     * Additional arguments to pass to IKVM.
     * @parameter
     */
    public List<String> ikvmArgs;

    /**
     * Additional DLLs (beyond mscorlib, System and System.Core) to reference. These can be
     * absoulte paths, or relative to {@code monoPath}.
     * @parameter
     */
    public List<String> dlls;

    /**
     * DLLs to copy into the target directory. These can be absoulte paths, or relative to {@code
     * ikvmPath}.
     * @parameter
     */
    public List<String> copyDlls;

    /**
     * Causes the plugin to copy {@code <type>dll</type>} dependencies into the target directory
     * (minus their version information) so that they can be consistently referenced from your
     * {@code .csproj} file.
     * @parameter expression="${ikvm.copydlldepends}" default-value="false"
     */
    public boolean copyDllDepends;

    /**
     * Creates a zero-sized stub file in the event that {@code ikvm.path} is not set and we cannot
     * build a proper artifact. This allows builds that include an ios submodule to not fail even
     * when built in environments that cannot build the ios component. One can also solve this
     * problem with Maven profiles but it is extremely messy and foists a lot of complexity onto
     * the end user.
     * @parameter expression="${ikvm.createstub}" default-value="false"
     */
    public boolean createStub;

    /**
     * If true, only includes class files from the dependent jar files, ignoring any XML, image or
     * other files that might also be in the jar. If this is false (the default) all of those
     * resources files are included in the generated DLL.
     * @parameter expression="${ikvm.onlycode}" default-value="false"
     */
    public boolean onlyCode;

    /**
     * The local repository to use when resolving dependencies.
     * @parameter default-value="${localRepository}"
     */
    public ArtifactRepository localRepository;

    public void execute () throws MojoExecutionException {
        // create our target directory if needed (normally the jar plugin does this, but we don't
        // run the jar plugin)
        File projectDir = new File(_project.getBuild().getDirectory());
        if (!projectDir.isDirectory()) {
            projectDir.mkdir();
        }

        // configure our artifact file
        File artifactFile = new File(projectDir, _project.getBuild().getFinalName() + ".dll");
        _project.getArtifact().setFile(artifactFile);

        if (ikvmPath == null) {
            // if requested, create a zero size artifact file
            if (createStub) {
                getLog().info("ikvm.path is not set. Creating stub IKVM artifact.");
                try {
                    artifactFile.createNewFile();
                } catch (IOException ioe) {
                    throw new MojoExecutionException(
                        "Unable to create stub artifact file: " + artifactFile, ioe);
                }
            } else {
                getLog().warn("ikvm.path is not set. Skipping IKVM build.");
            }
            return;
        }

        // sanity checks
        if (!monoPath.isDirectory()) {
            throw new MojoExecutionException(
                "mono.path refers to non- or non-existent directory: " + monoPath);
        }
        if (!ikvmPath.isDirectory()) {
            throw new MojoExecutionException(
                "ikvm.path refers to non- or non-existent directory: " + monoPath);
        }

        File ikvmc = new File(new File(ikvmPath, "bin"), "ikvmc.exe");
        if (!ikvmc.exists()) {
            throw new MojoExecutionException("Unable to find ikmvc at: " + ikvmc);
        }

        // resolve the (non-test) jar dependencies, all of which we'll include in our DLL
        List<File> javaDepends = new ArrayList<File>();
        List<Artifact> dllDepends = new ArrayList<Artifact>();
        try {
            for (Object a : _project.getArtifacts()) {
                Artifact artifact = (Artifact)a;
                getLog().debug("Considering artifact [" + artifact.getGroupId() + ":" +
                    artifact.getArtifactId() + "]");
                // I think @requiresDependencyResolution compile prevents this, but let's be sure.
                if (artifact.getScope().equals(Artifact.SCOPE_TEST)) {
                    continue;
                }
                if ("dll".equals(artifact.getType())) {
                    dllDepends.add(artifact);
                } else {
                    javaDepends.add(artifact.getFile());
                }
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to resolve dependencies.", e);
        }

        // create a command that executes mono on ikvmc.exe
        Commandline cli = new Commandline("mono");
        File ikvmcExe = new File(new File(ikvmPath, "bin"), "ikvmc.exe");
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
                getLog().warn("Don't specify -out:file directly. Set project.build.directory " +
                              "and project.build.finalName in your POM.");
                continue;
            }
            cli.createArgument().setValue(arg);
        }

        // add our output file
        cli.createArgument().setValue("-out:" + artifactFile.getAbsolutePath());

        // add our standard DLLs
        List<String> stdDlls = new ArrayList<String>();
        stdDlls.add("mscorlib.dll");
        stdDlls.add("System.dll");
        stdDlls.add("System.Core.dll");
        for (String dll : stdDlls) {
            cli.createArgument().setValue("-r:" + new File(monoPath, dll).getAbsolutePath());
        }

        // add our DLLs
        for (String dll : dlls) {
            if (stdDlls.contains(dll)) continue;
            if (new File(dll).isAbsolute()) {
                cli.createArgument().setValue("-r:" + dll);
            } else {
                cli.createArgument().setValue("-r:" + new File(monoPath, dll).getAbsolutePath());
            }
        }
        for (Artifact dll : dllDepends) {
            cli.createArgument().setValue("-r:" + dll.getFile().getAbsolutePath());
        }

        // if we're in onlyCode mode, then unpack our jars into a temporary directory and delete
        // all non-class files
        if (onlyCode) {
            File codeDir = new File(projectDir, "dll-classes");
            codeDir.mkdir();
            for (File depend : javaDepends) {
                try {
                    Expand ex = new Expand() {
                        @Override protected void extractFile (
                              File srcF, File dir, InputStream in, String entryName,
                              Date entryDate, boolean isDir) throws Exception {
                            if (entryName.endsWith(".class")) {
                                super.extractFile(srcF, dir, in, entryName, entryDate, isDir);
                            }
                        }
                    };
                    ex.setSrc(depend);
                    ex.setDest(codeDir);
                    ex.execute();
                } catch (Exception e) {
                    throw new MojoExecutionException("Error extracting classes from: " + depend, e);
                }
            }
            cli.createArgument().setValue("-recurse:" + codeDir.getAbsolutePath() + "/*.class");

        } else {
            // otherwise just add our jar files to the argument list
            for (File depend : javaDepends) {
                cli.createArgument().setValue(depend.getAbsolutePath());
            }
        }

        // copy any to-be-copied dlls
        for (String dll : copyDlls) {
            File dfile = new File(dll);
            if (!dfile.exists()) dfile = new File(ikvmPath, dll);
            if (!dfile.exists()) throw new MojoExecutionException(
                dll + " does not exist (nor does " + dfile.getPath() + ")");
            try {
                FileUtils.copyFile(dfile, new File(projectDir, dfile.getName()));
            } catch (IOException ioe) {
                throw new MojoExecutionException(
                    "Failed to copy " + dfile + " into " + projectDir, ioe);
            }
        }

        // if we want our dll depends copied, do that as well
        if (copyDllDepends) {
            for (Artifact dll : dllDepends) {
                try {
                    String name = dll.getArtifactId() + "." + dll.getType();
                    FileUtils.copyFile(dll.getFile(), new File(projectDir, name));
                } catch (IOException ioe) {
                    throw new MojoExecutionException(
                        "Failed to copy " + dll + " into " + projectDir, ioe);
                }
            }
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
