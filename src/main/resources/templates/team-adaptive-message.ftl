<#-- Rundeck -> Microsoft Teams Workflows webhook (Adaptive Card format). -->
<#-- https://learn.microsoft.com/en-us/microsoftteams/platform/webhooks-and-connectors/how-to/connectors-using#send-adaptive-cards-using-an-incoming-webhook -->
<#if executionData.job.group??>
    <#assign jobName="${executionData.job.group!''} / ${executionData.job.name!''}">
<#else>
    <#assign jobName="${executionData.job.name!''}">
</#if>
<#assign message="Execution #[${executionData.id!''}](${executionData.href!''}) of job [${jobName}](${executionData.job.href!''})">

<#if trigger == "start">
    <#assign state="started">
    <#assign stateColor="warning">
<#elseif trigger == "failure">
    <#assign state="failed">
    <#assign stateColor="attention">
<#elseif trigger == "avgduration">
    <#assign state="exceeded average duration">
    <#assign stateColor="warning">
<#elseif trigger == "retryablefailure">
    <#assign state="failed — will be retried">
    <#assign stateColor="warning">
<#elseif trigger == "unknown">
    <#assign state="unknown event">
    <#assign stateColor="accent">
<#else>
    <#assign state="${trigger}">
    <#assign stateColor="accent">
</#if>

<#-- Optional fields from the Rundeck execution-data reference. -->
<#-- Two-path lookups: legacy notifications put fields at top level,
     newer Rundeck versions nest them under "execution". -->
<#assign projectRaw = (executionData.project)!''>
<#if projectRaw == ''><#assign projectRaw = (executionData.execution.project)!''></#if>
<#assign startedRaw = (executionData.dateStartedW3c)!''>
<#if startedRaw == ''><#assign startedRaw = (executionData.execution.dateStartedW3c)!''></#if>
<#assign failedNodesRaw = (executionData.failedNodeListString)!''>
<#if failedNodesRaw == ''><#assign failedNodesRaw = (executionData.execution.failedNodeListString)!''></#if>

<#-- FactSet: base facts + conditionally-appended optional facts. -->
<#assign facts = [
  { "title": "Job", "value": "${jobName?json_string}" },
  { "title": "Status", "value": "${state?json_string}" },
  { "title": "Started By", "value": "${executionData.user?json_string}" }
]>
<#if projectRaw != ''>
    <#assign facts = facts + [ { "title": "Project", "value": "${projectRaw?json_string}" } ]>
</#if>
<#if startedRaw != ''>
    <#assign facts = facts + [ { "title": "Started", "value": "${startedRaw?json_string}" } ]>
</#if>
<#if failedNodesRaw != ''>
    <#assign facts = facts + [ { "title": "Failed Nodes", "value": "${failedNodesRaw?json_string}" } ]>
</#if>
<#if (executionData.job.description)?? && executionData.job.description != ''>
    <#assign facts = facts + [ { "title": "Description", "value": "${executionData.job.description?json_string}" } ]>
</#if>

{
  "type": "message",
  "attachments": [
    {
      "contentType": "application/vnd.microsoft.card.adaptive",
      "contentUrl": null,
      "content": {
        "$schema": "http://adaptivecards.io/schemas/adaptive-card.json",
        "type": "AdaptiveCard",
        "version": "1.4",
        "body": [
          {
            "type": "TextBlock",
            "text": "Rundeck — ${jobName?json_string}",
            "size": "Large",
            "weight": "Bolder",
            "wrap": true
          },
          {
            "type": "TextBlock",
            "text": "${state?json_string}",
            "color": "${stateColor}",
            "weight": "Bolder",
            "spacing": "None"
          },
          {
            "type": "TextBlock",
            "text": "${message?json_string}",
            "wrap": true,
            "markdown": true,
            "spacing": "Medium"
          },
          {
            "type": "FactSet",
            "facts": [
            <#list facts as f>
              { "title": "${f.title?json_string}", "value": "${f.value?json_string}" }<#if f_has_next>,</#if>
            </#list>
            ]
          }
        ],
        "actions": [
          {
            "type": "Action.OpenUrl",
            "title": "View in Rundeck",
            "url": "${executionData.href?json_string}"
          }
        ]
      }
    }
  ]
}