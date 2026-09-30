<#-- Rundeck -> Microsoft Teams Workflows webhook (Adaptive Card format). -->
<#-- https://learn.microsoft.com/en-us/microsoftteams/platform/webhooks-and-connectors/how-to/connectors-using#send-adaptive-cards-using-an-incoming-webhook -->
<#if executionData.job.group??>
    <#assign jobName="${executionData.job.group} / ${executionData.job.name}">
<#else>
    <#assign jobName="${executionData.job.name}">
</#if>
<#assign message="Execution #[${executionData.id}](${executionData.href}) of job [${jobName}](${executionData.job.href})">

<#if trigger == "start">
    <#assign state="started">
    <#assign stateColor="warning">
<#elseif trigger == "failure">
    <#assign state="failed">
    <#assign stateColor="attention">
<#else>
    <#assign state="succeeded">
    <#assign stateColor="good">
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
              { "title": "Job", "value": "${jobName?json_string}" },
              { "title": "Status", "value": "${state?json_string}" },
              { "title": "Started By", "value": "${executionData.user?json_string}" }
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