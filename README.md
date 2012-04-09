# ikvm-maven-plugin

This Maven plugin runs IKVM on a collection of Java jar files (defined by the
dependencies in the POM that includes this plugin).

The primary itch it scratches is to generate DLLs for use by the iOS backend of
the [PlayN] cross-platform game development library, but it should in theory be
usable for incorporating IKVM into any Maven build.

It defines a `dll` packaging type and generates a `dll` artifact.

## Usage

Here's a sample POM that demonstrates the use of this plugin:

    <?xml version="1.0" encoding="UTF-8"?>
    <project ...>
      <modelVersion>4.0.0</modelVersion>
      <groupId>foo</groupId>
      <artifactId>bar-ios</artifactId>
      <version>1.0-SNAPSHOT</version>
      <packaging>dll</packaging>

      <dependencies>
        <dependency>
          <groupId>foo</groupId>
          <artifactId>bar-core</artifactId>
          <version>${project.version}</version>
        </dependency>

        <dependency>
          <groupId>baz</groupId>
          <artifactId>bif</artifactId>
          <version>1.2</version>
        </dependency>
      </dependencies>

      <build>
        <plugins>
          <plugin>
            <groupId>com.samskivert</groupId>
            <artifactId>ikvm-maven-plugin</artifactId>
            <version>1.0</version>
            <!-- this lets Maven know that we define 'packaging: dll' -->
            <extensions>true</extensions>
            <configuration>
              <ikvmHome>${user.dir}/projects/ikvm-monotouch</ikvmHome>
              <ikvmArgs>
                <ikvmArg>-debug</ikvmArg>
              </ikvmArgs>
              <!-- these are additional referenced DLLs (beyond mscorlib, System and System.Core) -->
              <dlls>
                <dll>System.Data.dll</dll>
                <dll>OpenTK.dll</dll>
                <dll>monotouch.dll</dll>
                <dll>Mono.Data.Sqlite.dll</dll>
              </dlls>
            </configuration>
            <executions>
              <execution>
                <phase>package</phase>
                <goals>
                  <goal>ikvm</goal>
                </goals>
              </execution>
            </executions>
          </plugin>
        </plugins>
      </build>
    </project>

## License

ikvm-maven-plugin is released under the New BSD License, which can be found in
the [LICENSE] file.

[PlayN]: http://code.google.com/p/playn
[LICENSE]: https://github.com/samskivert/ikvm-maven-plugin/blob/master/LICENSE
