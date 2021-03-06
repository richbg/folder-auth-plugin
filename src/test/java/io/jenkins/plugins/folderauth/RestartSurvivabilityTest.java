package io.jenkins.plugins.folderauth;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.google.common.collect.ImmutableSet;
import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.AuthorizationStrategy;
import hudson.util.XStream2;
import io.jenkins.plugins.folderauth.roles.AgentRole;
import io.jenkins.plugins.folderauth.roles.FolderRole;
import io.jenkins.plugins.folderauth.roles.GlobalRole;
import jenkins.model.Jenkins;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static io.jenkins.plugins.folderauth.misc.PermissionWrapper.wrapPermissions;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RestartSurvivabilityTest {
    @Rule
    public RestartableJenkinsRule rule = new RestartableJenkinsRule();

    @Test
    @Issue("JENKINS-58485")
    public void shouldHaveSameConfigurationAfterRestart() {
        rule.addStep(new Statement() {
            @Override
            public void evaluate() throws Exception {
                rule.j.createProject(Folder.class, "folder");
                rule.j.jenkins.setSecurityRealm(rule.j.createDummySecurityRealm());
                rule.j.jenkins.setAuthorizationStrategy(createNewFolderBasedAuthorizationStrategy());
                rule.j.jenkins.addNode(rule.j.createSlave("foo", null, null));
                checkConfiguration();
            }
        });

        rule.addStep(new Statement() {
            @Override
            public void evaluate() {
                rule.j.jenkins.setSecurityRealm(rule.j.createDummySecurityRealm());
                checkConfiguration();

                // JENKINS-58485
                XStream2 xStream = new XStream2();
                String xml = xStream.toXML(rule.j.jenkins.getAuthorizationStrategy());
                assertFalse(xml.contains("ConcurrentHashMap$KeySetView"));
            }
        });
    }

    private FolderBasedAuthorizationStrategy createNewFolderBasedAuthorizationStrategy() {
        Set<GlobalRole> globalRoles = new HashSet<>();
        globalRoles.add(new GlobalRole("admin", wrapPermissions(Jenkins.ADMINISTER), ImmutableSet.of("admin")));
        globalRoles.add(new GlobalRole("read", wrapPermissions(Jenkins.READ), ImmutableSet.of("authenticated")));

        Set<FolderRole> folderRoles = new HashSet<>();
        folderRoles.add(new FolderRole("read", wrapPermissions(Item.READ), ImmutableSet.of("folder"),
            ImmutableSet.of("user1")));

        Set<AgentRole> agentRoles = new HashSet<>();
        agentRoles.add(new AgentRole("configureMaster", wrapPermissions(Computer.CONFIGURE),
            Collections.singleton("foo"), Collections.singleton("user1")));

        return new FolderBasedAuthorizationStrategy(globalRoles, folderRoles, agentRoles);
    }

    private void checkConfiguration() {
        Jenkins jenkins = Jenkins.get();
        try (ACLContext ignored = ACL.as(User.getById("admin", true))) {
            assertTrue(jenkins.hasPermission(Jenkins.ADMINISTER));
        }

        try (ACLContext ignored = ACL.as(User.getById("user1", true))) {
            Folder folder = (Folder) jenkins.getItem("folder");
            assertNotNull(folder);
            assertTrue(jenkins.hasPermission(Jenkins.READ));
            assertTrue(folder.hasPermission(Item.READ));
            assertFalse(folder.hasPermission(Item.CONFIGURE));
            assertFalse(jenkins.hasPermission(Jenkins.ADMINISTER));

            Computer computer = jenkins.getComputer("foo");
            assertNotNull(computer);
            assertTrue(computer.hasPermission(Computer.CONFIGURE));
            assertFalse(computer.hasPermission(Computer.DELETE));
        }

        AuthorizationStrategy a = Jenkins.get().getAuthorizationStrategy();
        assertTrue(a instanceof FolderBasedAuthorizationStrategy);
        FolderBasedAuthorizationStrategy strategy = (FolderBasedAuthorizationStrategy) a;
        assertEquals(strategy.getGlobalRoles().size(), 2);
        assertEquals(strategy.getFolderRoles().size(), 1);
        assertEquals(strategy.getAgentRoles().size(), 1);
    }
}
