package de.intranda.goobi.plugins;

/**
 * This file is part of a plugin for Goobi - a Workflow tool for the support of mass digitization.
 *
 * Visit the websites for more information.
 *          - https://goobi.io
 *          - https://www.intranda.com
 *          - https://github.com/intranda/goobi
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc., 59
 * Temple Place, Suite 330, Boston, MA 02111-1307 USA
 *
 */

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import de.intranda.goobi.plugins.generateproperty.ReflectionPathParser;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.PropertyManager;
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.digester.plugins.PluginException;
import org.goobi.beans.GoobiProperty;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.*;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.Fileformat;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;

@PluginImplementation
@Log4j2
public class GeneratePropertyStepPlugin implements IStepPluginVersion2 {
    private static final Pattern SPECIAL_PATTERN = Pattern.compile("\\{\\{(.*?)\\}\\}");
    
    @Getter
    private String title = "intranda_step_generate_property";
    @Getter
    private Process process;
    @Getter
    private Step step;

    private List<PropertyDefinition> propertyDefinitions;
    @Getter
    private VariableReplacer variableReplacer;
    @Getter
    private DigitalDocument digitalDocument;

    private String returnPath;

    @Data
    @RequiredArgsConstructor
    class PropertyDefinition {
        @NonNull
        private String name;
        @NonNull
        private String rawString;
        @NonNull
        private List<PropertyReplacement> replacements;

        public String generate() throws PluginException {
            var result = specialReplacement(rawString);
            result = getVariableReplacer().replace(result);
            for (PropertyReplacement r : replacements) {
                result = r.replace(result);
            }
            return result;
        }

        private String specialReplacement(@NonNull String value) {
            Matcher matcher = SPECIAL_PATTERN.matcher(value);
            while (matcher.find()) {
                String replacement = matcher.group(1);
                value = value.replace("{{" + replacement + "}}", specialReplacementValue(replacement));
                matcher = SPECIAL_PATTERN.matcher(value);
            }
            return value;
        }

        private String specialReplacementValue(String value) {
            try {
                return ReflectionPathParser.parse(process, value);
            } catch (NullPointerException e) {
                return "null";
            } catch (NoSuchMethodException e) {
                String message = "Error during special replacement, no such method: " + e.getMessage();
                Helper.setFehlerMeldung(message);
                Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, message);
                log.error(message, e);
                return value;
            } catch (Exception e) {
                String message = "Error during special replacement: " + e.getMessage();
                Helper.setFehlerMeldung(message);
                Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, message);
                log.error(message, e);
                throw new RuntimeException(message, e);
            }
        }
    }

    @RequiredArgsConstructor
    class PropertyReplacement {
        public String replace(String value) {
            return value.replaceAll(regex, replacement);
        }

        @NonNull
        private String regex;
        @NonNull
        private String replacement;
    }

    @Override
    public void initialize(Step step, String returnPath) {
        log.debug("================= Starting GeneratePropertyPlugin =================");
        this.step = step;
        this.process = step.getProzess();
        this.returnPath = returnPath;
        // TODO: Plugin initialization should also throw exceptions!
        try {
            this.digitalDocument = loadDigitalDocument();
            this.variableReplacer = loadVariableReplacer();
            SubnodeConfiguration myconfig = ConfigPlugins.getProjectAndStepConfig(title, step);
            loadPluginConfiguration(myconfig);
            log.info("GenerateProperty step plugin initialized");
        } catch (PluginException e) {
            log.error(e.getMessage());
            log.error(e);
        }
    }

    private DigitalDocument loadDigitalDocument() throws PluginException {
        try {
            return process.readMetadataFile().getDigitalDocument();
        } catch (ReadException | IOException | SwapException | PreferencesException | NullPointerException e1) {
            throw new PluginException("Errors happened while trying to initialize the Fileformat and VariableReplacer", e1);
        }
    }

    private VariableReplacer loadVariableReplacer() throws PluginException {
        try {
            Fileformat fileformat = process.readMetadataFile();
            return new VariableReplacer(fileformat != null ? fileformat.getDigitalDocument() : null,
                    process.getRegelsatz().getPreferences(), process, step);
        } catch (ReadException | IOException | SwapException | PreferencesException e1) {
            throw new PluginException("Errors happened while trying to initialize the Fileformat and VariableReplacer", e1);
        }
    }

    private void loadPluginConfiguration(SubnodeConfiguration config) throws PluginException {
        try {
            propertyDefinitions = config.configurationsAt("property")
                    .stream()
                    .map(this::parsePropertyDefinitions)
                    .collect(Collectors.toList());
        } catch (IllegalArgumentException e) {
            throw new PluginException("Error during property definition parsing!", e);
        }
    }

    private PropertyDefinition parsePropertyDefinitions(HierarchicalConfiguration config) throws IllegalArgumentException {
        String name = config.getString("@name");
        String value = config.getString("@value");
        List<PropertyReplacement> replacements = parseReplacements(config.configurationsAt("replace"));
        return new PropertyDefinition(name, value, replacements);
    }

    private @NonNull List<PropertyReplacement> parseReplacements(
            List<HierarchicalConfiguration> replacementConfigs) {
        return replacementConfigs.stream()
                .map(config -> new PropertyReplacement(config.getString("@regex", ""),
                        config.getString("@replacement", "")))
                .toList();
    }

    private GoobiProperty savePropertyWithValue(String name, String value) {
        GoobiProperty property = process.getProperties().stream()
                .filter(p -> name.equals(p.getPropertyName()))
                .findFirst()
                .orElse(new GoobiProperty(GoobiProperty.PropertyOwnerType.PROCESS));
        property.setOwner(process);
        property.setPropertyName(name);
        property.setPropertyValue(value);
        PropertyManager.saveProperty(property);
        return property;
    }

    @Override
    public PluginGuiType getPluginGuiType() {
        return PluginGuiType.NONE;
    }

    @Override
    public String getPagePath() {
        return null;
    }

    @Override
    public PluginType getType() {
        return PluginType.Step;
    }

    @Override
    public String cancel() {
        return returnPath;
    }

    @Override
    public String finish() {
        return returnPath;
    }
    
    @Override
    public int getInterfaceVersion() {
        return 0;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }
    
    @Override
    public boolean execute() {
        PluginReturnValue ret = run();
        return ret != PluginReturnValue.ERROR;
    }

    @Override
    public PluginReturnValue run() {
        try {
            for (PropertyDefinition pd : propertyDefinitions) {
                String propertyName = pd.getName();
                String propertyValue = pd.generate();
                savePropertyWithValue(propertyName, propertyValue);
            }
            log.info("GenerateProperty step plugin executed");
            return PluginReturnValue.FINISH;
        } catch (PluginException e) {
            log.error("Error during property generation: {}", e.getMessage(), e);
            return PluginReturnValue.ERROR;
        }
    }
}
