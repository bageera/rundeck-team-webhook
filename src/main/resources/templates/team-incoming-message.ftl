
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
  "text": "${message}",
  "title": "Rundeck Job ${jobName}",
  "themeColor": "${theme}",
  "sections": [
  {
      "title": "Job Details",
      "facts": [
        {
          "name": "Job Name",
          "value": "${jobName}"
        },
        {
          "name": "Job Status",
          "value": "${state}"
        },
        {
          "name": "Started By:",
          "value": "${executionData.user}"
        }
      ]
    }
  ],
  "potentialAction": [
    {
      "@context": "http://schema.org",
      "@type": "ViewAction",
      "name": "View in Rundeck",
      "target": ["${executionData.job.href}"]
    }
  ]
}
