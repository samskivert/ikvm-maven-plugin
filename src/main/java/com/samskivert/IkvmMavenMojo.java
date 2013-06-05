//
// ikvm-maven-plugin - generates C# DLL from Java code via IKVM
// http://github.com/samskivert/ikvm-maven-plugin/blob/master/LICENSE

package com.samskivert;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.codehaus.plexus.util.Expand;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;

import org.apache.maven.artifact.Artifact;
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
     * Location of the {@code ikvmc.exe} executable. Default: {@code ${ikvm.path}/bin/ikvmc.exe}.
     * @parameter expression="${ikvmc.path}"
     */
    public File ikvmcPath;

    /**
     * The location of the standard library DLLs.
     * @parameter expression="${dll.path}" default-value="/Developer/MonoTouch/usr/lib/mono/2.1"
     */
    public File dllPath;

    /**
     * Indicates that IKVM should be run via Mono rather than run directly as an executable. The
     * plugin defaults to running IKVM directly if we are running on Windows and using Mono
     * otherwise (on Mac and Linux), but this can force the use of Mono on Windows.
     * @parameter expression="${force.mono}" default-value="false"
     */
    public boolean forceMono;

    /**
     * Additional arguments to pass to IKVM.
     * @parameter
     */
    public List<String> ikvmArgs = new ArrayList<String>();

    /**
     * Additional DLLs (beyond mscorlib, System and System.Core) to reference. These can be
     * absolute paths, or relative to {@code dllPath}.
     * @parameter
     */
    public List<String> dlls = new ArrayList<String>();

    /**
     * DLLs to copy into the target directory. These can be absoulte paths, or relative to {@code
     * ikvmPath}.
     * @parameter
     */
    public List<String> copyDlls = new ArrayList<String>();

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
     * If true, check the output of ikvmc for "Warning" statements and abort the build.
     * @parameter expression="${ikvm.treatWarningsAsErrors}" default-value="false"
     */
    public boolean treatWarningsAsErrors;

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
        if (!ikvmPath.isDirectory()) {
            throw new MojoExecutionException(
                "ikvm.path refers to non- or non-existent directory: " + ikvmPath);
        }

        if (ikvmcPath == null) ikvmcPath = new File(new File(ikvmPath, "bin"), "ikvmc.exe");
        if (!ikvmcPath.exists()) {
            throw new MojoExecutionException("Unable to find ikmvc at: " + ikvmcPath);
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

        // create the command line that executes ikvmc.exe
        Commandline cli;
        // determine whether to run ikvmc.exe directly or to run via mono
        if (!forceMono && System.getProperty("os.name").contains("Windows")) {
            cli = new Commandline(ikvmcPath.getAbsolutePath());
        } else {
            cli = new Commandline("mono");
            cli.createArgument().setValue(ikvmcPath.getAbsolutePath());
        }

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
            cli.createArgument().setValue("-r:" + getDLLPath(dll));
        }

        // add our DLLs
        for (String dll : dlls) {
            if (stdDlls.contains(dll)) continue;
            cli.createArgument().setValue("-r:" + getDLLPath(dll));
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
            cli.createArgument().setValue("-recurse:" + codeDir.getAbsolutePath() + File.separator +
                                          "*.class");

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

            checkForWarnings(stdout);
            checkForWarnings(stderr);

        } catch (CommandLineException clie) {
            throw new MojoExecutionException("Executing ikvmc.exe failed", clie);
        }
    }

    private String getDLLPath (String dll) {
        // if the supplied DLL path is already absolute, just return it
        if (new File(dll).isAbsolute()) return dll;
        // if dllPath was set to a valid directory, use that
        if (dllPath.isDirectory()) return new File(dllPath, dll).getAbsolutePath();
        // otherwise just use the relative path and hope that things work
        return dll;
    }

    private void checkForWarnings (String output)
        throws MojoExecutionException
    {
        if (!treatWarningsAsErrors || output == null || output.length() == 0) return;
        Set<String> warnings = new HashSet<String>();
        String search = "Warning ";
        int searchLen = search.length(), count = 0, line = 0, nextLine = 0;
        for (; nextLine != -1; nextLine = output.indexOf('\n', line), line = nextLine + 1) {
            if (output.regionMatches(line, search, 0, searchLen)) {
                warnings.add(output.substring(line + searchLen, output.indexOf(':', line)));
                count++;
            }
        }
        if (count == 0) return;
        throw new MojoExecutionException(count + " warning" + (count > 1 ? "s" : "") +
            " detected in ikvmc output: " + StringUtils.join(warnings.iterator(), ", ") + ".");
    }

    /** @parameter default-value="${project}" */
    private MavenProject _project;
}
