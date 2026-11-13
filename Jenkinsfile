// The tests need no container runtime, so every branch verifies in a plain container
// without the Docker socket; only the image of the main branch is built, on a node labelled docker-socket.
// The platform artifacts come from Maven Central, so no credentials are bound. DOCKER_GID is the group id of the socket on the node.
// The branch condition below is text of the branch being built, so it does not keep other branches off the socket: the node labelled
// docker-socket must be restricted in Jenkins to the main branch of this job, and pull requests of untrusted forks must not run their
// own Jenkinsfile.
pipeline {
  agent none
  stages {
    stage('verify') {
      agent { docker { image 'maven:3.9.12-eclipse-temurin-25@sha256:4f82a03a7d6679281952d628131299b1be88d7030a49c6a2b7d2ba2642e44e3e' } }
      steps {
        sh './mvnw -B verify'
        stash name: 'jar', includes: 'target/mcp-server-adapter.jar'
      }
      post { always { junit allowEmptyResults: false, testResults: 'target/surefire-reports/TEST-*.xml' } }
    }
    stage('image') {
      when {
        beforeAgent true
        branch 'main'
      }
      agent {
        docker {
          image 'maven:3.9.12-eclipse-temurin-25@sha256:4f82a03a7d6679281952d628131299b1be88d7030a49c6a2b7d2ba2642e44e3e'
          label 'docker-socket'
          args "-v /var/run/docker.sock:/var/run/docker.sock --group-add ${env.DOCKER_GID ?: '999'}"
        }
      }
      steps {
        unstash 'jar'
        sh "docker build -t mcp-server-adapter:${env.GIT_COMMIT.take(7)} ."
      }
    }
  }
}
