import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.DumperOptions
import static org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK

vars = [:]
vars.namespace = "epm-rd-ru-edp-cicd"


pipeline {
    agent { node { label 'master' } }
    parameters {
        string(defaultValue: '',
                description: 'e.g. name_surname@epam.com, name_surname@epam.com',
                name: 'Student', trim: true)
        string(defaultValue: '',
                description: 'e.g module_1,module_<n> ',
                name: 'Project', trim: true)
        string(defaultValue: '',
                description: 'In case public repository e.g. https://github.com/epmd-edp/java-maven-springboot.git ' +
                        '\n In case internal repository add ssh link e.g. git@git.epam.com:epmd-edp/examples/basic/edp-springboot-helloworld.git',
                name: 'Git_Repository', trim: true)
        choice(choices: 'Java\nJavaScript', description: '', name: 'Language')
        choice(choices: 'Maven\nGradle\nNPM', description: '', name: 'BuildTool')
        booleanParam(defaultValue: false, description: 'Choose if ypur application needs route ', name: 'NeedRoute')
        string(defaultValue: '', description: 'Input if you check Need_Route', name: 'RouteName',trim: true)
        string(defaultValue: '', description: 'Input if you check Need_Route', name: 'RoutePath',trim: true)
        booleanParam(defaultValue: false, description: 'Choose if ypur application needs Database ', name: 'NeedDataBase')
        choice(choices: 'PostgreSQL', description: '', name: 'DataBaseName')
        choice(choices: 'postgres:9.6', description: '', name: 'DataBaseVersion')
        string(defaultValue: '', description: 'Enter database size in Mb e.g. 500 , 200 ', name: 'DataBaseSize', trim: true)
        choice(choices: 'efs\ngp2', description: '', name: 'StorageClass')
    }
    stages {
        stage("INIT") {
            steps {
                script {
                    if (params.Student.isEmpty() && params.Project.isEmpty()) {
                        error("[ERROR] Student and Project lists can not be empty")
                    } else {
                        initCodebase(params)
                    }
                }
            }
        }

        stage('CREATE APP REPRESENTATION') {
            steps {
                script {

                    codebase_template = WORKSPACE + "/codebase_test.yaml"
                    codebaseBranch_template = WORKSPACE + "/codebase_branch.yaml"
                    vars.codebase_filename = vars.studentName + "_" + vars.projectName
                    vars.codebaseBranch_filename = vars.studentName + "_" + vars.projectName + "_" + "masterBranch"

                    yamlRepresentation = getYamlRepresentation(codebase_template)
                    yamlCustomResource = configureYamlCustomResource(yamlRepresentation)
                    writeYamlCustomResourceFile(vars.codebase_filename,yamlCustomResource)

                    yamlCodebaseBranch = configureCodebaseBranch(codebaseBranch_template)
                    writeYamlCustomResourceFile(vars.codebaseBranch_filename, yamlCodebaseBranch)

                }
            }
        }
        stage('APPLY CR IN OPENSHIFT') {
            steps {
                script {
                    applyNewCustomResource(vars.codebase_filename,vars.namespace)
                    applyNewCustomResource(vars.codebaseBranch_filename, vars.namespace)
                }
            }
        }

    }
}

def configureCodebaseBranch(cbFile){
    codebaseBranchYaml = getYamlRepresentation(cbFile)

    codebaseBranchYaml.metadata.name = vars.studentName + "-" + vars.projectName + '-master'
    codebaseBranchYaml.spec.codebaseName = vars.studentName + "-" + vars.projectName

    return codebaseBranchYaml
}

def writeYamlCustomResourceFile(filename, yamlRepresentation){
    writeFile file: "${WORKSPACE}/${filename}.yaml", text: yamlToString(yamlRepresentation)
}


def configureYamlCustomResource(yamlRepresentation){
    yamlRepresentation.metadata.name = vars.studentName + "-" + vars.projectName
    yamlRepresentation.metadata.namespace = vars.namespace
    yamlRepresentation.spec.buildTool = vars.codebaseBuildTool
    yamlRepresentation.status.status = "initialized"
    yamlRepresentation.status.available = false

    if (vars.gitRepository){
        yamlRepresentation.spec.repository = [url:vars.gitRepository]
        yamlRepresentation.spec.strategy = "clone"
    } else {
        yamlRepresentation.spec.repository = null
        yamlRepresentation.spec.strategy = "create"
    }

    if (Boolean.valueOf(params.NeedRoute)) {
        yamlRepresentation.spec.route = [path: vars.routePath, site: routeName]
    } else {
        yamlRepresentation.spec.route = null
    }

    if (Boolean.valueOf(params.NeedDataBase)) {
        yamlRepresentation.spec.database = [capacity: vars.dbSize + "Mi", kind: vars.dbName, storage: vars.dbStorageClass, version: vars.dbVersion]
    } else {
        yamlRepresentation.spec.database = null
    }

    switch (vars.codebaseLanguage) {
        case "Java":
            yamlRepresentation.spec.framework = "SpringBoot"
            break
        case "JavaScript":
            yamlRepresentation.spec.framework = "React"
            break
    }

    return yamlRepresentation

}

def initCodebase(params) {
    userName = params.Student.toLowerCase() - "@epam.com"
    splitedUserName = userName.replaceAll('_',' ').split(' ')

    userNameLowerCase = splitedUserName[0]
    userSurnameLowerCase = splitedUserName[1]

    vars.studentName = userNameLowerCase + "-" + userSurnameLowerCase
    vars.projectName = params.Project.toLowerCase()
    vars.gitRepository = params.Git_Repository
    vars.codebaseLanguage = params.Language
    vars.codebaseBuildTool = params.BuildTool

    if (Boolean.valueOf(params.NeedRoute)) {
        vars.routeName = params.RouteName
        vars.routePath = params.RoutePath
    } else {
        println("[INFO] Route is not needed")
    }

    if (Boolean.valueOf(params.NeedDataBase)) {
        vars.dbName = params.DataBaseName
        vars.dbVersion = params.DataBaseVersion
        vars.dbSize = params.DataBaseSize
        vars.dbStorageClass = params.StorageClass
    } else {
        println("[INFO] Database is not needed")
    }
}


def getYamlRepresentation(filepath) {
    try {
        yamlRepresentation = readYaml file: filepath
        println("[DEBUG] File ${filepath} was successfully retrieve")
        return yamlRepresentation
    }
    catch (Exception ex) {
        error("[ERROR] Error during retrieving file. Reason: ${ex}")
    }

}

def applyNewCustomResource(filepath, namespace) {
    openshift.withCluster() {
        sh "oc apply -f ${filepath}.yaml -n ${namespace}"

    }
}


@NonCPS
String yamlToString(Object data) {
    def opts = new DumperOptions()
    opts.setDefaultFlowStyle(BLOCK)
    return new Yaml(opts).dump(data)
}
