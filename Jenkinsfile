// Thin wrapper: all logic is in ci/run.sh. The tests need no container runtime, so every branch verifies in a plain container
// without the Docker socket; only the image of the main branch is built, on a node labelled docker-socket.
// Registry access comes from Jenkins credentials (username and password, id mcp-maven-registry-readonly), MCP_MAVEN_REPOSITORY_URL
// from the job environment. DOCKER_GID is the group id of the socket on the node.
pipeline {
  agent none
  stages {
    stage('verify') {
      agent { docker { image 'maven:3.9.12-eclipse-temurin-25@sha256:4f82a03a7d6679281952d628131299b1be88d7030a49c6a2b7d2ba2642e44e3e' } }
      steps {
        withCredentials([usernamePassword(credentialsId: 'mcp-maven-registry-readonly',
            usernameVariable: 'MCP_MAVEN_REPOSITORY_USER', passwordVariable: 'MCP_MAVEN_REPOSITORY_PASSWORD')]) {
          sh 'ci/run.sh verify'
        }
        stash name: 'jar', includes: 'target/mcp-server-adapter.jar'
      }
      post { always { junit allowEmptyResults: false, testResults: 'target/surefire-reports/TEST-*.xml' } }
    }
    stage('image') {
      when { branch 'main' }
      agent {
        docker {
          image 'maven:3.9.12-eclipse-temurin-25@sha256:4f82a03a7d6679281952d628131299b1be88d7030a49c6a2b7d2ba2642e44e3e'
          label 'docker-socket'
          args "-v /var/run/docker.sock:/var/run/docker.sock --group-add ${env.DOCKER_GID ?: '999'}"
        }
      }
      steps {
        unstash 'jar'
        sh 'ci/run.sh image'
      }
    }
  }
}
