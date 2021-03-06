package br.com.ingenieux.mojo.beanstalk;

/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import br.com.ingenieux.mojo.aws.AbstractAWSMojo;
import br.com.ingenieux.mojo.beanstalk.cmd.env.waitfor.WaitForEnvironmentCommand;
import br.com.ingenieux.mojo.beanstalk.cmd.env.waitfor.WaitForEnvironmentContext;
import br.com.ingenieux.mojo.beanstalk.cmd.env.waitfor.WaitForEnvironmentContextBuilder;
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalkClient;
import com.amazonaws.services.elasticbeanstalk.model.ConfigurationOptionSetting;
import com.amazonaws.services.elasticbeanstalk.model.DescribeApplicationsRequest;
import com.amazonaws.services.elasticbeanstalk.model.EnvironmentDescription;
import org.apache.commons.collections.comparators.ReverseComparator;
import org.apache.maven.plugin.MojoExecutionException;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.String.format;
import static org.apache.commons.lang.StringUtils.*;

public abstract class AbstractBeanstalkMojo extends
        AbstractAWSMojo<AWSElasticBeanstalkClient> {
    protected List<ConfigurationOptionSetting> getOptionSettings(
            ConfigurationOptionSetting[] optionSettings) {
        ConfigurationOptionSetting[] arrOptionSettings = optionSettings;

        if (null == arrOptionSettings || 0 == arrOptionSettings.length)
            return Collections.emptyList();

        return Arrays.asList(arrOptionSettings);
    }

    protected EnvironmentDescription lookupEnvironment(String applicationName, String environmentRef) throws MojoExecutionException {
        final WaitForEnvironmentContext ctx = new WaitForEnvironmentContextBuilder().withApplicationName(applicationName).withEnvironmentRef(environmentRef).build();
        final Collection<EnvironmentDescription> environments = new WaitForEnvironmentCommand(this).lookupInternal(ctx);
        return handleResults(environments);
    }

    protected EnvironmentDescription handleResults(Collection<EnvironmentDescription> environments)
            throws MojoExecutionException {
        int len = environments.size();

        if (1 == len)
            return environments.iterator().next();

        handleNonSingle(len);

        return null;
    }

    protected void handleNonSingle(int len)
            throws MojoExecutionException {
        if (0 == len) {
            throw new MojoExecutionException("No environments found");
        } else {
            throw new MojoExecutionException("Multiple environments found matching the supplied parameters (may you file a bug report?)");
        }
    }

    /**
     * Boolean predicate for harmful/placebo options
     * <p/>
     * I really mean harmful - If you mention a terminated environment settings,
     * Elastic Beanstalk will accept, but this might lead to inconsistent
     * states, specially when creating / listing environments.
     * <p/>
     * Trust me on this one.
     *
     * @param environmentId environment id to lookup
     * @param optionSetting option setting
     * @return true if this is not needed
     */
    protected boolean harmfulOptionSettingP(String environmentId, ConfigurationOptionSetting optionSetting) {
        boolean bInvalid = isBlank(optionSetting.getValue());

        if (!bInvalid)
            bInvalid = (optionSetting.getNamespace().equals(
                    "aws:cloudformation:template:parameter") && optionSetting
                    .getOptionName().equals("AppSource"));

        if (!bInvalid)
            bInvalid = (optionSetting.getNamespace().equals(
                    "aws:elasticbeanstalk:sns:topics") && optionSetting
                    .getOptionName().equals("Notification Topic ARN"));

		/*
         * TODO: Apply a more general regex instead
		 */
        if (!bInvalid && isNotBlank(environmentId))
            bInvalid = (optionSetting.getValue().contains(environmentId));

        return bInvalid;
    }

    public String lookupTemplateName(String applicationName, String templateName) {
        if (!hasWildcards(defaultString(templateName)))
            return templateName;

        getLog().info(format("Template Name %s contains wildcards. A Lookup is needed", templateName));

        Collection<String> configurationTemplates = getConfigurationTemplates(applicationName);

        for (String configTemplateName : configurationTemplates)
            getLog().debug(format(" * Found Template Name: %s", configTemplateName));

		/*
		 * TODO: Research and Review valid characters / applicable glob
		 * replacements
		 */
        Pattern templateMask = Pattern.compile(templateName.replaceAll("\\.", "\\\\.").replaceAll("\\Q*\\E", ".*").replaceAll("\\Q?\\E", "."));

        for (String s : configurationTemplates) {
            Matcher m = templateMask.matcher(s);
            if (m.matches()) {
                getLog().info(format("Selecting: %s", s));
                return s;
            }
        }

        getLog().info("Not found");

        return null;
    }

    public boolean hasWildcards(String input) {
        return (input.indexOf('*') != -1 || input.indexOf('?') != -1);
    }

    @SuppressWarnings("unchecked")
    protected List<String> getConfigurationTemplates(String applicationName) {
        List<String> configurationTemplates = getService().describeApplications(new DescribeApplicationsRequest().withApplicationNames(applicationName)).getApplications().get(0).getConfigurationTemplates();

        Collections.<String>sort(configurationTemplates, new ReverseComparator(String.CASE_INSENSITIVE_ORDER));

        return configurationTemplates;
    }

    public String ensureSuffix(String cname) {
        cname = defaultString(cname);
        if (! cname.endsWith(".elasticbeanstalk.com"))
            cname += ".elasticbeanstalk.com";

        return cname;
    }

    public String ensureSuffixStripped(String cnamePrefix) {
        return defaultString(cnamePrefix).replaceAll("\\Q.elasticbeanstalk.com\\E$", "");
    }
}
