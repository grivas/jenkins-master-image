@groovy.lang.Grab('org.yaml:snakeyaml:1.19')

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import groovy.xml.MarkupBuilder
import hudson.security.AuthorizationStrategy
import hudson.security.HudsonPrivateSecurityRealm
import hudson.slaves.EnvironmentVariablesNodeProperty
import javaposse.jobdsl.plugin.GlobalJobDslSecurityConfiguration
import jenkins.model.Jenkins
import jenkins.plugins.slack.SlackNotifier
import net.sf.json.JSONObject
import org.csanchez.jenkins.plugins.kubernetes.ContainerTemplate
import org.csanchez.jenkins.plugins.kubernetes.KubernetesCloud
import org.csanchez.jenkins.plugins.kubernetes.PodEnvVar
import org.csanchez.jenkins.plugins.kubernetes.PodTemplate
import org.csanchez.jenkins.plugins.kubernetes.volumes.HostPathVolume
import org.csanchez.jenkins.plugins.kubernetes.volumes.SecretVolume
import org.jenkinsci.plugins.googlelogin.GoogleOAuth2SecurityRealm
import org.kohsuke.stapler.StaplerRequest
import org.yaml.snakeyaml.Yaml

import static com.cloudbees.plugins.credentials.CredentialsScope.GLOBAL

loadJenkinsConfigFrom('/etc/jenkins/jenkins-configuration.yaml').with {
    if (containsKey('seed')) createSeedJob(get('seed'))
    if (containsKey('envVars')) createEnvironmentVariables(get('envVars'))
    if (containsKey('ssh')) createSshCredentias(get('ssh'))
    if (containsKey('views')) setupViews(get('views'))
    if (containsKey('kubernetes')) setupKubernetesPlugin(get('kubernetes'))
    if (containsKey('slack')) setupSlackIntegration(get('slack'))
    if (containsKey('auth')) setupAuthenticationAuthorization(get('auth'))
}

static loadJenkinsConfigFrom(String path) {
    new Yaml().load(new File(path).text).jenkins
}

static createSshCredentias(Map<String, String> ssh) {
    ssh.each {
        id, key ->
            def globalCredentials = Jenkins.instance.getExtensionList(SystemCredentialsProvider)[0].getCredentials()
            def keyContents = new String(key.content.decodeBase64())
            def sshCredentials = new BasicSSHUserPrivateKey(
                    GLOBAL,
                    id,
                    id,
                    new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(keyContents),
                    key.passphrase,
                    ""
            )
            globalCredentials.add(sshCredentials)
            println "SSH credentials for $id successfully added"
    }
}

static setupSlackIntegration(Map<String, String> slack) {
    def slackNotifier = Jenkins.instance.getExtensionList(
            SlackNotifier.DescriptorImpl
    )[0]
    def params = [
            slackTeamDomain    : slack.domain,
            slackToken         : slack.token,
            slackRoom          : "",
            slackBuildServerUrl: slack.jenkinsUrl,
            slackSendAs        : ""
    ]
    def req = [
            getParameter: { name -> params[name] }
    ] as StaplerRequest
    slackNotifier.configure(req, null)
    slackNotifier.save()
    println "Slack integration successfully setup"
}

static setupKubernetesPlugin(Map<String, Object> kubernetes) {
    def templates = kubernetes.templates.collect {
        name, pod ->
            def volumes = pod.volumes.hostPath.collect { new HostPathVolume(it.mountPath, it.hostPath) }
            volumes += pod.volumes.secret.collect { new SecretVolume(it.mountPath, it.secretName) }
            def envVars = pod.envVars.collect { new PodEnvVar(it.key, it.value) }
            def containers = pod.containers.collect {
                def container = new ContainerTemplate(it.name, it.image, it.command, it.args)
                container.setTtyEnabled(it.tty ?: false)
                container
            }
            def template = new PodTemplate()
            template.setName(name)
            template.setLabel(name)
            template.setContainers(containers)
            template.setVolumes(volumes)
            template.setPrivileged(true)
            template.setEnvVars(envVars)
            template
    }

    def kubernetesCloud = new KubernetesCloud(kubernetes.name)
    kubernetesCloud.templates = templates
    kubernetesCloud.serverUrl = kubernetes.serverUrl
    kubernetesCloud.namespace = kubernetes.namespace
    kubernetesCloud.jenkinsUrl = kubernetes.jenkinsUrl
    kubernetesCloud.connectTimeout = kubernetes.connectTimeout
    kubernetesCloud.readTimeout = kubernetes.readTimeout
    kubernetesCloud.retentionTimeout = kubernetes.retentionTimeout

    kubernetesCloud.setDefaultsProviderTemplate(kubernetes.defaultTemplate)
    println "Kubernetes cloud successfully added"
    Jenkins.instance.clouds.add(kubernetesCloud)
    Jenkins.instance.save()
}

static setupAuthenticationAuthorization(Map<String, String> auth) {
    if (auth.disable) {
        println "Skipping authentication and authorization setup"
        Jenkins.instance.setAuthorizationStrategy(new AuthorizationStrategy.Unsecured())
        Jenkins.instance.setSecurityRealm(new HudsonPrivateSecurityRealm(true))
    } else if (auth.google){
        auth.google.with {
            Jenkins.instance.setSecurityRealm(new GoogleOAuth2SecurityRealm(clientId, clientSecret, domain))
            //Authorizes all authenticated users as administrators.
            def strategy = new hudson.security.GlobalMatrixAuthorizationStrategy()
            strategy.add(Jenkins.ADMINISTER, 'authenticated')
            Jenkins.instance.setAuthorizationStrategy(strategy)
            println "Google auth successfully setup"
        }
    }
    Jenkins.instance.setSlaveAgentPort(50000)
    Jenkins.instance.save()
}

static createEnvironmentVariables(envVars) {
    envVars.each {
        key, value ->
            def endpointEnvVariable = new EnvironmentVariablesNodeProperty.Entry(name, value)
            Jenkins.instance.getGlobalNodeProperties().add(new EnvironmentVariablesNodeProperty(endpointEnvVariable))
            println "Environment variable $name set to $value"
    }
}

static createSeedJob(seed) {
    def globalJobDslSecurity = Jenkins.instance.getExtensionList(GlobalJobDslSecurityConfiguration)[0]
    globalJobDslSecurity.configure(null, new JSONObject())

    def jobConfig = File.createTempFile("seedJob", "config")
    new FileWriter(jobConfig).with { fw ->
        new MarkupBuilder(fw).with {
            project {
                triggers(class: 'vector') {
                    "hudson.triggers.SCMTrigger" {
                        spec { mkp.yield("H/5 * * * *") }
                        ignorePostCommitHooks { mkp.yield(false) }
                    }
                }
                builders {
                    "hudson.plugins.gradle.Gradle" {
                        tasks { mkp.yield("clean test") }
                        useWrapper { mkp.yield(true) }
                        fromRootBuildScriptDir { mkp.yield(true) }
                    }
                    "javaposse.jobdsl.plugin.ExecuteDslScripts" {
                        targets { mkp.yield("jobs/**/*Job*.groovy") }
                        usingScriptText { mkp.yield(false) }
                    }
                }
                scm(class: "hudson.plugins.git.GitSCM") {
                    userRemoteConfigs {
                        "hudson.plugins.git.UserRemoteConfig" {
                            name { mkp.yield("origin") }
                            url { mkp.yield(seed.repository) }
                            if (seed.credentials){
                                credentialsId { mkp.yield(seed.credentials) }
                            }
                        }
                    }
                    branches {
                        "hudson.plugins.git.BranchSpec" {
                            name { mkp.yield("master") }
                        }
                    }
                }
            }
        }
    }
    jobConfig.withInputStream { is ->
        Jenkins.instance.createProjectFromXML("seed", is)
    }
}


