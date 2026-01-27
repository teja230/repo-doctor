package dev.repodoctor.model;

public enum BuildTool {
    MAVEN("Maven", "mvn -Duser.home=/workspace test"),
    GRADLE("Gradle", "./gradlew test"),
    NODE("Node.js", "npm test"),
    PYTHON("Python", "python3 -m pytest -v"),
    UNKNOWN("Unknown", "echo 'Unknown build tool'");

    private final String name;
    private final String testCommand;

    BuildTool(String name, String testCommand) {
        this.name = name;
        this.testCommand = testCommand;
    }

    public String getName() {
        return name;
    }

    public String getTestCommand() {
        return testCommand;
    }
}
