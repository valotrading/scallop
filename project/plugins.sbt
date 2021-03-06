addSbtPlugin("cc.spray" % "sbt-revolver" % "0.5.0")

addSbtPlugin("me.lessis" % "jot" % "0.1.0")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.7.3")

resolvers += "Sonatype OSS Releases" at "https://oss.sonatype.org/content/repositories/releases"

addSbtPlugin("com.github.aloiscochard" %% "xsbt-fmpp-plugin" % "0.2")

resolvers += Resolver.url("sbt-plugin-releases",
  new URL("http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-releases/"))(Resolver.ivyStylePatterns)

resolvers += Resolver.url("scalasbt", new URL("http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-releases"))(Resolver.ivyStylePatterns)

resolvers += "Scala-Tools Maven2 Snapshots Repository" at "http://scala-tools.org/repo-snapshots"

addSbtPlugin("org.ensime" % "ensime-sbt-cmd" % "0.0.7")

addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "2.0.0")

addSbtPlugin("com.jsuereth" % "xsbt-gpg-plugin" % "0.5")