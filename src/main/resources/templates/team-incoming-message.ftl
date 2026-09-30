<#-- Rundeck -> Microsoft Teams Incoming Webhook (Office 365 Connector card) -->
<#if executionData.job.group??>
    <#assign jobName="${executionData.job.group} / ${executionData.job.name}">
<#else>
    <#assign jobName="${executionData.job.name}">
</#if>
<#assign message="Execution #[${executionData.id}](${executionData.href}) of job [${jobName}](${executionData.job.href})">

<#if trigger == "start">
    <#assign state="started">
    <#assign theme="fff356">
<#elseif trigger == "failure">
    <#assign state="failed">
    <#assign theme="E81123">
<#else>
    <#assign state="succeeded">
    <#assign theme="439e12">
</#if>

{
  "text": "${message?json_string}",
  "title": "Rundeck Job ${jobName?json_string}",
  "themeColor": "${theme}",
  "sections": [
    {
      "title": "Job Details",
      "facts": [
        {
          "name": "Job Name",
          "value": "${jobName?json_string}"
        },
        {
          "name": "Job Status",
          "value": "${state}"
        },
        {
          "name": "Started By",
          "value": "${executionData.user?json_string}"
        }
      ]
    }
  ],
  "potentialAction": [
    {
      "@context": "http://schema.org",
      "@type": "ViewAction",
      "name": "View in Rundeck",
      "target": [ "${executionData.job.href?json_string}" ]
    }
  ]
}