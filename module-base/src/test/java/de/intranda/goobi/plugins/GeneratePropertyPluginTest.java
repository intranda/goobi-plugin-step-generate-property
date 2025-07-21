package de.intranda.goobi.plugins;

import static org.easymock.EasyMock.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.powermock.api.easymock.PowerMock.mockStatic;
import static org.powermock.api.easymock.PowerMock.replay;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.persistence.managers.PropertyManager;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.goobi.beans.*;
import org.goobi.beans.Process;
import org.goobi.production.enums.PluginReturnValue;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.powermock.api.easymock.PowerMock;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.enums.StepStatus;
import de.sub.goobi.metadaten.MetadatenHelper;
import de.sub.goobi.persistence.managers.MetadataManager;
import de.sub.goobi.persistence.managers.ProcessManager;
import ugh.dl.Fileformat;
import ugh.dl.Prefs;
import ugh.fileformats.mets.MetsMods;

@RunWith(PowerMockRunner.class)
@PrepareForTest({ MetadatenHelper.class, VariableReplacer.class, ConfigurationHelper.class, ConfigPlugins.class, ProcessManager.class,
        MetadataManager.class, PropertyManager.class })
@PowerMockIgnore({ "javax.management.*", "javax.xml.*", "org.xml.*", "org.w3c.*", "javax.net.ssl.*", "jdk.internal.reflect.*" })
public class GeneratePropertyPluginTest {
    private static final String DEFAULT_RETURN_PAGE = "pageBefore";

    private static String resourcesFolder;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File processDirectory;
    private File metadataDirectory;
    private Process process;
    private Step step;
    private Prefs prefs;


    private SubnodeConfiguration pluginConfiguration;
    private GeneratePropertyStepPlugin plugin;
    private Capture<GoobiProperty> propertyCaptor;

    @BeforeClass
    public static void setUpClass() throws Exception {
        resourcesFolder = "src/test/resources/"; // for junit tests in eclipse

        if (!Files.exists(Paths.get(resourcesFolder))) {
            resourcesFolder = "target/test-classes/"; // to run mvn test from cli or in jenkins
        }

        String log4jFile = resourcesFolder + "log4j2.xml"; // for junit tests in eclipse

        System.setProperty("log4j.configurationFile", log4jFile);
    }

    @Before
    public void setUp() throws Exception {
        metadataDirectory = folder.newFolder("metadata");
        processDirectory = new File(metadataDirectory + File.separator + "1");
        processDirectory.mkdirs();
        String metadataDirectoryName = metadataDirectory.getAbsolutePath() + File.separator;
        Path metaSource = Paths.get(resourcesFolder, "meta.xml");
        Path metaTarget = Paths.get(processDirectory.getAbsolutePath(), "meta.xml");
        Files.copy(metaSource, metaTarget);

        Path anchorSource = Paths.get(resourcesFolder, "meta_anchor.xml");
        Path anchorTarget = Paths.get(processDirectory.getAbsolutePath(), "meta_anchor.xml");
        Files.copy(anchorSource, anchorTarget);

        PowerMock.mockStatic(ConfigurationHelper.class);
        ConfigurationHelper configurationHelper = EasyMock.createMock(ConfigurationHelper.class);
        EasyMock.expect(ConfigurationHelper.getInstance()).andReturn(configurationHelper).anyTimes();
        EasyMock.expect(configurationHelper.getMetsEditorLockingTime()).andReturn(1800000l).anyTimes();
        EasyMock.expect(configurationHelper.isAllowWhitespacesInFolder()).andReturn(false).anyTimes();
        EasyMock.expect(configurationHelper.useS3()).andReturn(false).anyTimes();
        EasyMock.expect(configurationHelper.isUseProxy()).andReturn(false).anyTimes();
        EasyMock.expect(configurationHelper.getGoobiContentServerTimeOut()).andReturn(60000).anyTimes();
        EasyMock.expect(configurationHelper.getGoobiFolder()).andReturn(resourcesFolder).anyTimes();
        EasyMock.expect(configurationHelper.getMetadataFolder()).andReturn(metadataDirectoryName).anyTimes();
        EasyMock.expect(configurationHelper.getRulesetFolder()).andReturn(resourcesFolder).anyTimes();
        EasyMock.expect(configurationHelper.getScriptsFolder()).andReturn(resourcesFolder).anyTimes();
        EasyMock.expect(configurationHelper.getProcessImagesMainDirectoryName()).andReturn("00469418X_media").anyTimes();
        EasyMock.expect(configurationHelper.isUseMasterDirectory()).andReturn(true).anyTimes();
        EasyMock.expect(configurationHelper.getConfigurationFolder()).andReturn(resourcesFolder).anyTimes();
        EasyMock.expect(configurationHelper.getMaxDatabaseConnectionRetries()).andReturn(0).anyTimes();
        EasyMock.expect(configurationHelper.getNumberOfMetaBackups()).andReturn(0).anyTimes();
        EasyMock.replay(configurationHelper);

        PowerMock.mockStatic(VariableReplacer.class);
        EasyMock.expect(VariableReplacer.findRegexMatches(EasyMock.anyString(), EasyMock.anyObject())).andReturn(Collections.emptyList()).anyTimes();
        prefs = new Prefs();
        prefs.loadPrefs(resourcesFolder + "ruleset.xml");
        Fileformat ff = new MetsMods(prefs);
        ff.read(metaTarget.toString());

        PowerMock.mockStatic(MetadatenHelper.class);
        EasyMock.expect(MetadatenHelper.getMetaFileType(EasyMock.anyString())).andReturn("mets").anyTimes();
        EasyMock.expect(MetadatenHelper.getFileformatByName(EasyMock.anyString(), EasyMock.anyObject())).andReturn(ff).anyTimes();
        EasyMock.expect(MetadatenHelper.getMetadataOfFileformat(EasyMock.anyObject(), EasyMock.anyBoolean()))
                .andReturn(Collections.emptyMap())
                .anyTimes();
        PowerMock.replay(MetadatenHelper.class);

        PowerMock.mockStatic(MetadataManager.class);
        MetadataManager.updateMetadata(1, Collections.emptyMap());
        MetadataManager.updateJSONMetadata(1, Collections.emptyMap());
        PowerMock.replay(MetadataManager.class);

        PowerMock.mockStatic(PropertyManager.class);
        EasyMock.expect(PropertyManager.getPropertiesForObject(anyInt(), anyObject())).andReturn(Collections.emptyList()).anyTimes();

        PowerMock.replay(ConfigurationHelper.class);

        process = getProcess();

        Ruleset ruleset = PowerMock.createMock(Ruleset.class);
        ruleset.setTitel("ruleset");
        ruleset.setDatei("ruleset.xml");
        EasyMock.expect(ruleset.getDatei()).andReturn("ruleset.xml").anyTimes();
        process.setRegelsatz(ruleset);
        EasyMock.expect(ruleset.getPreferences()).andReturn(prefs).anyTimes();
        PowerMock.replay(ruleset);

    }

    private SubnodeConfiguration loadPluginConfiguration(String testFileName) throws ConfigurationException {
        XMLConfiguration xmlConfig = new XMLConfiguration();
        xmlConfig.load(getClass().getResource("/" + testFileName + ".xml"));
        xmlConfig.setDelimiterParsingDisabled(true);
        xmlConfig.setExpressionEngine(new XPathExpressionEngine());
        return xmlConfig.configurationAt("//config");
    }

    private void setupPluginConfiguration(String configurationName) throws ConfigurationException {
        pluginConfiguration = loadPluginConfiguration(configurationName);
    }

    private void initializate() {
        plugin = new GeneratePropertyStepPlugin();
        setupConfigurationFileMocking(pluginConfiguration);
        plugin.initialize(step, DEFAULT_RETURN_PAGE);
    }

    private void setupConfigurationFileMocking(SubnodeConfiguration config) {
        mockStatic(ConfigPlugins.class);
        expect(ConfigPlugins.getProjectAndStepConfig(plugin.getTitle(), step))
                .andReturn(config)
                .anyTimes();
        replay(ConfigPlugins.class);
    }

    public Process getProcess() {
        Project project = new Project();
        project.setTitel("GeneratePropertyProject");

        Process process = new Process();
        process.setTitel("00469418X");
        process.setProjekt(project);
        process.setId(1);
        List<Step> steps = new ArrayList<>();
        step = new Step();
        step.setReihenfolge(1);
        step.setProzess(process);
        step.setTitel("test step");
        step.setBearbeitungsstatusEnum(StepStatus.OPEN);
        User user = new User();
        user.setVorname("Firstname");
        user.setNachname("Lastname");
        user.setStandort("Office");
        step.setBearbeitungsbenutzer(user);
        steps.add(step);

        process.setSchritte(steps);

        try {
            createProcessDirectory(processDirectory);
        } catch (IOException e) {
        }

        return process;
    }

    private void createProcessDirectory(File processDirectory) throws IOException {

        // image folder
        File imageDirectory = new File(processDirectory.getAbsolutePath(), "images");
        imageDirectory.mkdir();
        // master folder
        File masterDirectory = new File(imageDirectory.getAbsolutePath(), "00469418X_master");
        masterDirectory.mkdir();

        // media folder
        File mediaDirectory = new File(imageDirectory.getAbsolutePath(), "00469418X_media");
        mediaDirectory.mkdir();

        // TODO add some file
    }

    private void setupVariableReplacerEcho() {
        EasyMock.expect(VariableReplacer.simpleReplace(EasyMock.anyString(), EasyMock.anyObject())).andAnswer((IAnswer<String>) () -> {
            return (String) getCurrentArguments()[0];
        }).anyTimes();
        PowerMock.replay(VariableReplacer.class);
    }

    private void setupVariableReplacerValue(String value) {
        EasyMock.expect(VariableReplacer.simpleReplace(EasyMock.anyString(), EasyMock.anyObject())).andReturn(value).anyTimes();
        PowerMock.replay(VariableReplacer.class);
    }

    private void setupPropertyCreation() {
        propertyCaptor = EasyMock.newCapture();
        PropertyManager.saveProperty(EasyMock.capture(propertyCaptor));
        PowerMock.expectLastCall().once();
    }

    private void verifyPropertyCreation(String name, String value) {
        GoobiProperty property = propertyCaptor.getValue();
        assertEquals(name, property.getPropertyName());
        assertEquals(value, property.getPropertyValue());
    }

    @Test
    public void staticProperty_expectCorrectPropertyGeneration() throws ConfigurationException, IOException {
        setupPluginConfiguration("static");
        initializate();
        setupPropertyCreation();
        PowerMock.replay(PropertyManager.class);
        setupVariableReplacerEcho();

        assertEquals(PluginReturnValue.FINISH, plugin.run());

        PowerMock.verify(PropertyManager.class);
        verifyPropertyCreation("Static Text", "This is static");
    }

    @Test
    public void staticPropertyWithReplacement_expectCorrectPropertyGeneration() throws ConfigurationException, IOException {
        setupPluginConfiguration("static-with-replacement");
        initializate();
        setupPropertyCreation();
        PowerMock.replay(PropertyManager.class);
        setupVariableReplacerEcho();

        assertEquals(PluginReturnValue.FINISH, plugin.run());

        PowerMock.verify(PropertyManager.class);
        verifyPropertyCreation("Static Text", "This is static");
    }

    @Test
    public void variable_expectCorrectPropertyGeneration() throws ConfigurationException, IOException {
        setupPluginConfiguration("variable");
        initializate();
        setupPropertyCreation();
        PowerMock.replay(PropertyManager.class);
        setupVariableReplacerValue("12345678");

        assertEquals(PluginReturnValue.FINISH, plugin.run());

        PowerMock.verify(PropertyManager.class);
        verifyPropertyCreation("Variable", "12345678");
    }

    @Test
    @Ignore("Doesn't work due to bad mocking")
    public void variableWithStatic_expectCorrectPropertyGeneration() throws ConfigurationException, IOException {
        setupPluginConfiguration("variable-with-static");
        initializate();
        setupPropertyCreation();
        PowerMock.replay(PropertyManager.class);
        setupVariableReplacerValue("12345678");

        assertEquals(PluginReturnValue.FINISH, plugin.run());

        PowerMock.verify(PropertyManager.class);
        verifyPropertyCreation("Combined", "12345678_suffix");
    }

    @Test
    public void specialUserLocation_expectCorrectPropertyGeneration() throws ConfigurationException, IOException {
        setupPluginConfiguration("user-location");
        initializate();
        setupPropertyCreation();
        PowerMock.replay(PropertyManager.class);
        setupVariableReplacerEcho();

        assertEquals(PluginReturnValue.FINISH, plugin.run());

        PowerMock.verify(PropertyManager.class);
        verifyPropertyCreation("User Location", "Office");
    }
}
