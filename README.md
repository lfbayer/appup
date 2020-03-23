# appup

[![][Build Status img]][Build Status]
[![][license img]][license]
[![][Maven Central img]][Maven Central]
[![][Javadocs img]][Javadocs]

A Java application bootstrap library

#### Overview
Appup provides

- Application startup and lifecycle management
- Native code management (honors Bundle-NativeCode jar manifest headers)
- Service registration
- A contribution registry that shares the plugin.xml syntax of the eclipse plugin registry.

#### Maven Artifact
```xml
<dependency>
    <groupId>com.lbayer</groupId>
    <artifactId>appup</artifactId>
    <version>0.1.13</version>
</dependency>
```

#### Usage

Appup is executed by running the AppupLauncher as your class and passing it a configuration file.

For example:
```
java -cp libs/* com.lbayer.appup.application.AppupLauncher -c config.ini
```

#### Configuration File

All properties in the configuration file are automatically added to the java System properties.

##### Appup Specific Properties

&#128288;``appup.startClasses``<br/>
A comma separated list of lifecycle class names
 
#### Lifecycle Classes
Lifecycle classes are the main entry point for a application within appup.

When the application is started the list of start classes (as defined by 
the appup.startClasses system property) are each constructed and any method 
with an ``@PostConstruct`` annotation are invoked.

Methods with an ``@PreDestroy`` annotation will be invoked when the application is stopped.

#### IContribRegistry

#### IAppupRuntime

#### Native Code

Appup automatically scans the classpath for any jars that contain the ``Bundle-NativeCode``
manifest header, and loads the appropriate platform's library.

#### Dependency Injection

Uses ``javax.annotation.Resource`` annotation to automatically inject services into services.

[Build Status]:https://travis-ci.org/lfbayer/appup
[Build Status img]:https://travis-ci.org/lfbayer/appup.svg?branch=master

[license]:LICENSE
[license img]:https://img.shields.io/badge/license-Apache%202-blue.svg
   
[Maven Central]:https://maven-badges.herokuapp.com/maven-central/com.lbayer/appup
[Maven Central img]:https://maven-badges.herokuapp.com/maven-central/com.lbayer/appup/badge.svg
   
[Javadocs]:http://javadoc.io/doc/com.lbayer/appup
[Javadocs img]:http://javadoc.io/badge/com.lbayer/appup.svg
 
