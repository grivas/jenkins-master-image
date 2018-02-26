jenkins {
    // General environment variables
    envVars {

    }

    auth {
        disable = true
    }
    // Google authentication parameters
//    auth {
//        domain = ""
//        clientSecret = ""
//        clientId = ""
//    }
    // Slack integration
//    slack {
//        domain = ""
//        jenkinsUrl = ""
//        token = ""
//    }
    // SSH private keys
    ssh {
        key {
            content = """\
                xxxxx
            """.stripIndent()
            passphrase = "yyy"
        }
    }
//    // kubernetes configuration
//    kubernetes {
//        // Configuration name
//        name = "kubernetes"
//        // Kubernetes api url. Using local as slaves are expected to run in the same machine
//        serverUrl = "https://kubernetes.default.svc.cluster.local"
//        // Namespace that build slaves will belong to
//        namespace = "build-server"
//        // Jenkins master URL. Since master runs in the same cluster and also belongs to
//        // build-server namespace, slaves can access it by using only it's service name.
//        jenkinsUrl = 'http://jenkins-master'
//        // The maximum number of concurrently running slave containers that Kubernetes is allowed to run
//        containerCap = "5"
//        connectTimeout = 5
//        readTimeout = 15
//        retentionTimeout = 0
//        defaultTemplate = "klarity-slave"
//    }

    seed {
        repository = "https://github.com/grivas/jenkins-pipeline-jobs.git"
//        credentials = "bitbucket"
    }
}
