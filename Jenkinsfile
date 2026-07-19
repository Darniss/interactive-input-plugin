/*
 * Build configuration for ci.jenkins.io.
 * See the pipeline library docs for all options:
 *   https://github.com/jenkins-infra/pipeline-library/
 *
 * Runs the full Maven build (compile, unit tests, SpotBugs, HPI) across the
 * platforms/JDKs below. jenkins.version 2.555.2 supports both Java 17 and 21.
 */
buildPlugin(
  useContainerAgent: true,
  configurations: [
    [platform: 'linux',   jdk: 21],
    [platform: 'windows', jdk: 17],
  ]
)
