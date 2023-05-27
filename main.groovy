node("masterLin"){
  try {
    def latest_version
    def artifactId
    def groupId
    def context
     stage('Скачиваем скрипты c Git') {
       echo 'Step 1'
       echo "Скачиваем репозиторий со скриптами управления."
       checkout([$class: 'GitSCM', branches: [[name: "*/${BRANCH}"]], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [[url: "${URLGIT}", credentialsId: "bitbucket_cred"]]])
           }

     stage('Парсинг конфигурационных файлов и скачивание дистрибутива') {
       echo 'Step 2'
       getConfig()
       context=this
       context.wrap([$class: 'BuildUser']){ // plugin BUILD USER VARS
       this.currentUser = context.env.BUILD_USER ?: "Задание запущено с помощью токена"
       this.currentUserEmail = context.env.BUILD_USER_EMAIL ?: false
       notifyStart()
     }
    }
     stage('Выполнение операции') {
        echo 'Step 3'
        try {
          def result
          
      
          dir("./deploy/") {
              timestamps{
                echo "Используем ansiblePlaybook.yml"
                           
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
            echo "Действие выполнено успешно result: ${result} на стенде: ${HOSTNAME}"
          } else {
            echo "Действие провалено result: ${result} на стенде: ${HOSTNAME}"
            error ("Операция провалена, смотри лог!")
          }
        } catch(Ex) {
          echo "${Ex.toString()}"
          currentBuild.result = "FAILED"
          error ("Операция провалена, смотри лог!")
        }
      }
      stage('Смоук тест') {
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
      echo "Группа рассылки: ${mailgroup}"
      for(url in configXml.Subsystem.url){
          url = url.toString()
          urls.push("${url}")
        }
      echo "Будем проверять URLs: ${urls}"
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
               println ("Статус страницы:" +response.status)
               if (response.status == 200) {
                      echo "Страница доступна"
               } else {
                      sleep 300
                      response=httpRequest url: "${url_test}", validResponseCodes: '200:600'
                      if (response.status == 200) {
                              echo "Страница доступна"
                      } else {
                              error ("Страница недоступна, нужно проверить вручную.")
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
             subject: "Выполняется операция config_ws на сервере PSI ${urls}",
             body: """В рамках проведения работ выполняется операция config_ws на сервере PSI ${urls}
             На ТЕСТОВОМ стенде ${HOSTNAME}
             Лог процесса доступен по ссылке: ${env.BUILD_URL}console
             Workspace: ${env.BUILD_URL}execution/node/3/ws/
             Задание запущено пользователем ${this.currentUser}
             ${this.currentUserEmail}""",
             to: "Patsyuk-MY@mail.sbrf.ru"
     )
}
def notifySuccessful() {
     emailext (
             mimeType: 'text/plain',
             subject: "Операция config_ws выполнена УСПЕШНО на стенде PSI ${HOSTNAME}",
             body: """Операция config_ws на '${env.JOB_NAME} [${env.BUILD_NUMBER}]' выполнена УСПЕШНО:
             На ТЕСТОВОМ стенде ${HOSTNAME}
             Проверьте лог по ссылке: ${env.BUILD_URL}console
             Workspace: ${env.BUILD_URL}execution/node/3/ws/""",
             to: "${mailgroup}"
     ) 
}
def notifyFailed() {
     emailext (
             mimeType: 'text/plain',
             attachLog: true, compressLog: true,
             subject: "Операция config_ws ПРОВАЛЕНА на стенде PSI ${HOSTNAME}",
             body: """Операция playbook на '${env.JOB_NAME} [${env.BUILD_NUMBER}]' ПРОВАЛЕНА:
             На ТЕСТОВОМ стендe ${HOSTNAME}
             Проверьте лог по ссылке: ${env.BUILD_URL}console
             Workspace: ${env.BUILD_URL}execution/node/3/ws/""",
             to: "${mailgroup}"
     )
}
