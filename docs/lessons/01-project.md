# 01 — A reproducible Java project

Read `pom.xml`, then `Main.java`. Maven compiles `src/main/java`, tests `src/test/java`, and packages a runnable JAR. The Maven Wrapper downloads the pinned Maven distribution, so different machines use the same build tool. Java itself must already be installed.

Run `./mvnw clean verify` and `java -jar target/astra.jar`. No networking exists yet.

Question: why is a build file useful when you could run `javac` yourself? Answer: it records the language target, dependencies, test runner, and packaging instructions for everyone who studies the project.
