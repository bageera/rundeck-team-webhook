<#-- Rundeck -> Microsoft Teams legacy connector webhook (MessageCard format; retired upstream, kept for pinned configs). -->
<#if executionData.job.group??>
    <#assign jobName="${executionData.job.group!''} / ${executionData.job.name!''}">
<#else>
    <#assign jobName="${executionData.job.name!''}">
</#if>
<#assign message="Execution #[${executionData.id!''}](${executionData.href!''}) of job [${jobName}](${executionData.job.href!''})">

<#if trigger == "start">
    <#assign state="started">
    <#assign theme="FFF356">
<#elseif trigger == "failure">
    <#assign state="failed">
    <#assign theme="E81123">
<#elseif trigger == "avgduration">
    <#assign state="exceeded average duration">
    <#assign theme="FFB900">
<#elseif trigger == "retryablefailure">
    <#assign state="failed — will be retried">
    <#assign theme="FFB900">
<#elseif trigger == "unknown">
    <#assign state="unknown event">
    <#assign theme="6B69F4">
<#else>
    <#assign state="${trigger}">
    <#assign theme="6B69F4">
</#if>

<#-- Optional fields; two-path lookup for legacy/newer Rundeck layouts -->
<#assign projectRaw = (executionData.project)!''>
<#if projectRaw == ''><#assign projectRaw = (executionData.execution.project)!''></#if>
<#assign failedNodesRaw = (executionData.failedNodeListString)!''>
<#if failedNodesRaw == ''><#assign failedNodesRaw = (executionData.execution.failedNodeListString)!''></#if>

<#assign facts = [
  { "name": "Job Name", "value": "${jobName?json_string}" },
  { "name": "Job Status", "value": "${state?json_string}" },
  { "name": "Started By", "value": "${executionData.user?json_string}" }
]>
<#if projectRaw != ''>
    <#assign facts = facts + [ { "name": "Project", "value": "${projectRaw?json_string}" } ]>
</#if>
<#if failedNodesRaw != ''>
    <#assign facts = facts + [ { "name": "Failed Nodes", "value": "${failedNodesRaw?json_string}" } ]>
</#if>
<#if (executionData.job.description)?? && executionData.job.description != ''>
    <#assign facts = facts + [ { "name": "Description", "value": "${executionData.job.description?json_string}" } ]>
</#if>

{
  "text": "${message?json_string}",
  "title": "Rundeck Job ${jobName?json_string}",
  "themeColor": "${theme}",
  "sections": [
    {
      "title": "Job Details",
      "facts": [
      <#list facts as f>
        { "name": "${f.name?json_string}", "value": "${f.value?json_string}" }<#if f_has_next>,</#if>
      </#list>
      ]
    }
  ],
  "potentialAction": [
    {
      "@context": "http://schema.org",
      "@type": "ViewAction",
      "name": "View in Rundeck",
      "target": [ "${executionData.href?json_string}" ]
    }
  ]
}