node("masterLin"){
  try {
    def latest_version
    def artifactId
    def groupId
    def context
     stage('Downloading scripts from Git') {
       echo 'Step 1'
       echo "Downloading the repository with management scripts."
       checkout([$class: 'GitSCM', branches: [[name: "*/${BRANCH}"]], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [[url: "${URLGIT}", credentialsId: "bitbucket_cred"]]])
           }

     stage('Parsing configuration files and downloading distribution') {
       echo 'Step 2'
       getConfig()
       context=this
       context.wrap([$class: 'BuildUser']){ // plugin BUILD USER VARS
       this.currentUser = context.env.BUILD_USER ?: "Task started using a token"
       this.currentUserEmail = context.env.BUILD_USER_EMAIL ?: false
       notifyStart()
     }
    }
     stage('Performing operation') {
        echo 'Step 3'
        try {
          def result
          
      
          dir("./deploy/") {
              timestamps{
                echo "Using ansiblePlaybook.yml"
                           
                ansiblePlaybook (
                                        playbook: "./config_ws.yml",
                                        inventory: "../inventories/IFT_WAS_TEST_INVENTORY/hosts",
                                        colorized: false,
                                        credentialsId: "wasadmin_ssh_cred",
                                        extras: "-e HOSTNAME=${HOSTNAME} -e ID_TB=${ID_TB} -e saveParam=${saveParam} "
                                )
                result = 0
                 
                }
            }
          
          if(result==0) {
            echo "Action executed successfully. Result ${result} on host: ${HOSTNAME}"
          } else {
            echo "Action failed. Result ${result} on host: ${HOSTNAME}"
            error ("Операция провалена, смотри лог!")
          }
        } catch(Ex) {
          echo "${Ex.toString()}"
          currentBuild.result = "FAILED"
          error ("Operation failed, check the log!")
        }
      }
      stage('Smoke test') {
        echo 'Step 5'
        smokeTest()
      }
      notifySuccessful()
      cleanWs()
  } catch (e) {
      currentBuild.result = "FAILED"
      notifyFailed()
      cleanWs()
      throw e
   }
}
def getConfig() {
  echo 'getting config'
  config = readFile("config/IFT_WAS_TEST/${HOSTNAME}.xml")
  configXml = new XmlSlurper().parseText(config)
  urls = []
  try {
      mailgroup = configXml.Subsystem.mailgroup.toString()
      echo "Mailing group: ${mailgroup}"
      for(url in configXml.Subsystem.url){
          url = url.toString()
          urls.push("${url}")
        }
      echo "Checking URLs: ${urls}"
  } catch (Ex) {
      echo "Error=${Ex.toString()}"
  }
  configXml = null
}

def smokeTest() {
  echo "URLs: ${urls}"
  try {
         for(url_test in urls) {
               response=httpRequest url: "${url_test}", validResponseCodes: '200:600'
               println ("Page status:" +response.status)
               if (response.status == 200) {
                      echo "Page is accessible"
               } else {
                      sleep 300
                      response=httpRequest url: "${url_test}", validResponseCodes: '200:600'
                      if (response.status == 200) {
                              echo "Page is accessible"
                      } else {
                              error ("Page is not accessible, manual verification is required.")
                      }
               }
         }
  } catch (Ex) {
    echo "Error=${Ex.toString()}"
  }
}
def notifyStart() {
     emailext (
             mimeType: 'text/plain',
             subject: "Executing config_ws operation on PSI server ${urls}",
             body: """As part of the work, the config_ws operation is being executed on the PSI server ${urls}
             On the TEST stand ${HOSTNAME}
             Log of the process is available at: ${env.BUILD_URL}console
             Workspace: ${env.BUILD_URL}execution/node/3/ws/
             The task was started by user ${this.currentUser}
             ${this.currentUserEmail}""",
             to: "Patsyuk-MY@mail.sbrf.ru"
     )
}
def notifySuccessful() {
     emailext (
             mimeType: 'text/plain',
             subject: "config_ws operation completed SUCCESSFULLY on PSI server ${HOSTNAME}",
             body: """The config_ws operation on '${env.JOB_NAME} [${env.BUILD_NUMBER}]' completed SUCCESSFULLY:
             On the TEST stand ${HOSTNAME}
             Check the log at: ${env.BUILD_URL}console
             Workspace: ${env.BUILD_URL}execution/node/3/ws/""",
             to: "${mailgroup}"
     ) 
}
def notifyFailed() {
     emailext (
             mimeType: 'text/plain',
             attachLog: true, compressLog: true,
             subject: "config_ws operation FAILED on PSI server ${HOSTNAME}",
             body: """The playbook operation on '${env.JOB_NAME} [${env.BUILD_NUMBER}]' FAILED:
             On the TEST stand ${HOSTNAME}
             Check the log at: ${env.BUILD_URL}console
             Workspace: ${env.BUILD_URL}execution/node/3/ws/""",
             to: "${mailgroup}"
     )
}
