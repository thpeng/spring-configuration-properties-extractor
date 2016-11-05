# spring-configuration-properties-extractor

Maven plugin for extracting all possible configurations set with the spring @Value annotations. For each annotation found the plugin decides if a configuration is mandatory because no default is set or if it is optional. Optional values are printed with an uncomment, mandatory are printed with a replacement convention from urban code. 

usage: 

        <plugin>
            <groupId>ch.thp.proto</groupId>
            <artifactId>spring-configuration-properties-extraxtor-plugin</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <executions>
                <execution>
                    <phase>compile</phase>
                    <configuration>
                        <packageScan>ch.thp</packageScan>
                    </configuration>
                    <goals>
                        <goal>extract</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
        
Possible output in target/template.properties: 
        
        #default values are: 'none set', found in classes: Test, TestTest
        bla.blubb=@bla.blubb@
        #default values are: 'none set', found in classes: Test
        blubb.blubb=@blubb.blubb@
        #default values are: 'hellohello', 'hello', found in classes: Test
        #<replace-me>blubb.hello=@blubb.hello@
        #default values are: 'hello', found in classes: Test
        #<replace-me>blubb.yarr=@blubb.yarr@

does not work with xml based spring configuration. sorry.
