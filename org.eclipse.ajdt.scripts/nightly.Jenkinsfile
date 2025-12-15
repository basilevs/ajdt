pipeline {
  agent any
      
  options {
    buildDiscarder(logRotator(numToKeepStr: '20', daysToKeepStr: '7', artifactNumToKeepStr: '1'))
  }
  tools {
    maven 'apache-maven-3.9.11'
  }
  stages {
    stage("Publish") {
      steps {
        sh 'mvn -Pe431 -Peclipse-sign -DskipTests clean verify'
        sshagent(['projects-storage.eclipse.org-bot-ssh']) {
          sh 'java org.eclipse.ajdt.scripts/src/org/eclipse/ajdt/scripts/PublishUpdateSite.java genie.aspectj@projects-storage.eclipse.org nightly /home/data/httpd/download.eclipse.org/ajdt'
        }
      }
    }
  }
}
