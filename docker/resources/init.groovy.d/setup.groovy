import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import groovy.json.JsonOutput
import groovy.xml.MarkupBuilder
import hudson.security.AuthorizationStrategy
import hudson.security.HudsonPrivateSecurityRealm
import hudson.slaves.EnvironmentVariablesNodeProperty
import hudson.util.Secret
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

import static com.cloudbees.plugins.credentials.CredentialsScope.GLOBAL

loadJenkinsConfigFrom('/etc/jenkins/jenkins-configuration.groovy').with {
    if (isSet('seed')) createSeedJob(seed)
    if (isSet('envVars')) createEnvironmentVariables(envVars)
    if (isSet('ssh')) createSshCredentias(ssh)
    if (isSet('views')) setupViews(views)
    if (isSet('kubernetes')) setupKubernetesPlugin(kubernetes)
    if (isSet('slack')) setupSlackIntegration(slack)
    if (isSet('auth')) setupAuthenticationAuthorization(auth)
}

static loadJenkinsConfigFrom(String path) {
    new ConfigSlurper().parse(new File(path).text).jenkins
}

static createSshCredentias(Map<String, String> ssh) {
    ssh.each {
        id, key ->
            def globalCredentials = Jenkins.instance.getExtensionList(SystemCredentialsProvider)[0].getCredentials()
            def sshCredentials = new BasicSSHUserPrivateKey(
                    GLOBAL,
                    id,
                    id,
                    new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(key.content),
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
    Jenkins.instance.setNumExecutors(0)
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

    def kubernetesCloud = new KubernetesCloud(kubernetes.name, templates, kubernetes.serverUrl, kubernetes.namespace, kubernetes.jenkinsUrl,
            kubernetes.containerCap, kubernetes.connectTimeout, kubernetes.readTimeout, kubernetes.retentionTimeout)
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
    } else {
        Jenkins.instance.setSecurityRealm(new GoogleOAuth2SecurityRealm(auth.clientId, auth.clientSecret, auth.domain))
        //Authorizes all authenticated users as administrators.
        def strategy = new hudson.security.GlobalMatrixAuthorizationStrategy()
        strategy.add(Jenkins.ADMINISTER, 'authenticated')
        Jenkins.instance.setAuthorizationStrategy(strategy)
        println "Authentication and authorization successfully setup"
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
                            if (!seed.credentials.isEmpty()){
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


