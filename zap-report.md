# ZAP Scanning Report

ZAP by [Checkmarx](https://checkmarx.com/).


## Summary of Alerts

| Risk Level | Number of Alerts |
| --- | --- |
| High | 0 |
| Medium | 0 |
| Low | 0 |
| Informational | 2 |




## Insights

| Level | Reason | Site | Description | Statistic |
| --- | --- | --- | --- | --- |
| Low | Exceeded High | http://127.0.0.1:5090 | Percentage of responses with status code 4xx | 99 % |
| Info | Informational | http://127.0.0.1:5090 | Percentage of endpoints with content type application/json | 2 % |
| Info | Informational | http://127.0.0.1:5090 | Percentage of endpoints with content type application/problem+json | 97 % |
| Info | Informational | http://127.0.0.1:5090 | Percentage of endpoints with method GET | 77 % |
| Info | Informational | http://127.0.0.1:5090 | Percentage of endpoints with method POST | 17 % |
| Info | Informational | http://127.0.0.1:5090 | Percentage of endpoints with method PUT | 5 % |
| Info | Informational | http://127.0.0.1:5090 | Count of total endpoints | 76    |







## Alerts

| Name | Risk Level | Number of Instances |
| --- | --- | --- |
| A Client Error response code was returned by the server | Informational | 96 |
| Non-Storable Content | Informational | Systemic |




## Alert Detail



### [ A Client Error response code was returned by the server ](https://www.zaproxy.org/docs/alerts/100000/)



##### Informational (High)

### Description

A response code of 422 was returned by the server.
This may indicate that the application is failing to handle unexpected input correctly.
Raised by the 'Alert on HTTP Response Code Error' script

* URL: http://127.0.0.1:5090
  * Node Name: `http://127.0.0.1:5090`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090
  * Node Name: `http://127.0.0.1:5090`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/
  * Node Name: `http://127.0.0.1:5090/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/1285926570366077452
  * Node Name: `http://127.0.0.1:5090/1285926570366077452`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api
  * Node Name: `http://127.0.0.1:5090/api`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api
  * Node Name: `http://127.0.0.1:5090/api`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/
  * Node Name: `http://127.0.0.1:5090/api/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/7505206498244273692
  * Node Name: `http://127.0.0.1:5090/api/7505206498244273692`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1
  * Node Name: `http://127.0.0.1:5090/api/v1`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1
  * Node Name: `http://127.0.0.1:5090/api/v1`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/
  * Node Name: `http://127.0.0.1:5090/api/v1/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/5474637801933466182
  * Node Name: `http://127.0.0.1:5090/api/v1/5474637801933466182`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/devices
  * Node Name: `http://127.0.0.1:5090/api/v1/devices`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/devices
  * Node Name: `http://127.0.0.1:5090/api/v1/devices`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/devices/
  * Node Name: `http://127.0.0.1:5090/api/v1/devices/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/devices/5424563950441576092
  * Node Name: `http://127.0.0.1:5090/api/v1/devices/5424563950441576092`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/devices/actuator/health
  * Node Name: `http://127.0.0.1:5090/api/v1/devices/actuator/health`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `405`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections/
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections/59267869857402651
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections/59267869857402651`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections/plan%3Ffrom=from&to=to
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections/plan (from,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections/plan%3Ffrom=from&to=http%253A%252F%252Fwww.google.com%253A80%252F
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections/plan (from,to)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections/plan/
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections/plan/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections/units%3Fperiod=http%253A%252F%252Fwww.google.com%252F&property=property&tenant=tenant
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections/units (period,property,tenant)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections/units%3Fperiod=period&property=property&tenant=tenant
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections/units (period,property,tenant)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections/units%3Fperiod=period&property=http%253A%252F%252Fwww.google.com%252F&tenant=tenant
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections/units (period,property,tenant)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections/units/
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections/units/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections/visitId
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections/visitId`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections/visitId
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections/visitId`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections/visitId/
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections/visitId/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections/visitId/3064660076994335204
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections/visitId/3064660076994335204`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections/visitId/images
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections/visitId/images`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections/visitId/images
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections/visitId/images`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections/visitId/images/
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections/visitId/images/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections/visitId/images/3235165704085460299
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections/visitId/images/3235165704085460299`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections/visitId/images/imageId
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections/visitId/images/imageId`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections/visitId/images/imageId/
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections/visitId/images/imageId/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/me/
  * Node Name: `http://127.0.0.1:5090/api/v1/me/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/meters
  * Node Name: `http://127.0.0.1:5090/api/v1/meters`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/meters
  * Node Name: `http://127.0.0.1:5090/api/v1/meters`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/meters/
  * Node Name: `http://127.0.0.1:5090/api/v1/meters/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/meters/8478434652064808651
  * Node Name: `http://127.0.0.1:5090/api/v1/meters/8478434652064808651`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/meters/meterId
  * Node Name: `http://127.0.0.1:5090/api/v1/meters/meterId`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/meters/meterId/
  * Node Name: `http://127.0.0.1:5090/api/v1/meters/meterId/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/properties
  * Node Name: `http://127.0.0.1:5090/api/v1/properties`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/properties
  * Node Name: `http://127.0.0.1:5090/api/v1/properties`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/properties/
  * Node Name: `http://127.0.0.1:5090/api/v1/properties/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/properties/9042262248357641832
  * Node Name: `http://127.0.0.1:5090/api/v1/properties/9042262248357641832`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/properties/search%3Fq=q&zone=zone&done=done&type=type
  * Node Name: `http://127.0.0.1:5090/api/v1/properties/search (done,q,type,zone)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/properties/search%3Fq=http%253A%252F%252Fwww.google.com%252F&zone=zone&done=done&type=type
  * Node Name: `http://127.0.0.1:5090/api/v1/properties/search (done,q,type,zone)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/properties/search/
  * Node Name: `http://127.0.0.1:5090/api/v1/properties/search/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/readings
  * Node Name: `http://127.0.0.1:5090/api/v1/readings`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/readings/
  * Node Name: `http://127.0.0.1:5090/api/v1/readings/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/readings/2578629814054903056
  * Node Name: `http://127.0.0.1:5090/api/v1/readings/2578629814054903056`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/readings/mine%3Fperiod=period
  * Node Name: `http://127.0.0.1:5090/api/v1/readings/mine (period)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/readings/mine%3Fperiod=http%253A%252F%252Fwww.google.com%252F
  * Node Name: `http://127.0.0.1:5090/api/v1/readings/mine (period)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/readings/mine/
  * Node Name: `http://127.0.0.1:5090/api/v1/readings/mine/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/readings/transactionId
  * Node Name: `http://127.0.0.1:5090/api/v1/readings/transactionId`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/readings/transactionId/
  * Node Name: `http://127.0.0.1:5090/api/v1/readings/transactionId/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/readings/transactionId/6599637112775253138
  * Node Name: `http://127.0.0.1:5090/api/v1/readings/transactionId/6599637112775253138`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/readings/transactionId/images
  * Node Name: `http://127.0.0.1:5090/api/v1/readings/transactionId/images`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/readings/transactionId/images/
  * Node Name: `http://127.0.0.1:5090/api/v1/readings/transactionId/images/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/readings/transactionId/images/8446813808880351148
  * Node Name: `http://127.0.0.1:5090/api/v1/readings/transactionId/images/8446813808880351148`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/readings/transactionId/images/imageId
  * Node Name: `http://127.0.0.1:5090/api/v1/readings/transactionId/images/imageId`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/readings/transactionId/images/imageId/
  * Node Name: `http://127.0.0.1:5090/api/v1/readings/transactionId/images/imageId/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/summary%3Fperiod=period&zone=zone
  * Node Name: `http://127.0.0.1:5090/api/v1/summary (period,zone)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/summary%3Fperiod=http%253A%252F%252Fwww.google.com%252F&zone=zone
  * Node Name: `http://127.0.0.1:5090/api/v1/summary (period,zone)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/summary/
  * Node Name: `http://127.0.0.1:5090/api/v1/summary/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/sync
  * Node Name: `http://127.0.0.1:5090/api/v1/sync`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/sync/
  * Node Name: `http://127.0.0.1:5090/api/v1/sync/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/sync/7727999254401383444
  * Node Name: `http://127.0.0.1:5090/api/v1/sync/7727999254401383444`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/sync/meters%3Fzone=http%253A%252F%252Fwww.google.com%252F
  * Node Name: `http://127.0.0.1:5090/api/v1/sync/meters (zone)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/sync/meters/
  * Node Name: `http://127.0.0.1:5090/api/v1/sync/meters/`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/devices/register
  * Node Name: `http://127.0.0.1:5090/api/v1/devices/register ()({code,model,androidVersion,appVersion})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `422`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/devices/register
  * Node Name: `http://127.0.0.1:5090/api/v1/devices/register ()({code,model,androidVersion,appVersion})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/devices/register/
  * Node Name: `http://127.0.0.1:5090/api/v1/devices/register/ ()({code,model,androidVersion,appVersion})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections ()({visitId,periodCode,propertyCode,tenantCode,startedAtUtc,finishedAtUtc,units:[{resultId,unitId,result,peopleSeen,occupantName,reasons:[],note,photoCount,unitCode,buildingName,category}],personMet,hasSignature,deviceId,latitude,longitude,gpsAccuracyM,atProperty})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections ()({visitId,periodCode,propertyCode,tenantCode,startedAtUtc,finishedAtUtc,units:[{resultId,unitId,result,peopleSeen,occupantName,reasons:[],note,photoCount,unitCode,buildingName,category}],personMet,hasSignature,deviceId,latitude,longitude,gpsAccuracyM,atProperty})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections/
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections/ ()({visitId,periodCode,propertyCode,tenantCode,startedAtUtc,finishedAtUtc,units:[{resultId,unitId,result,peopleSeen,occupantName,reasons:[],note,photoCount,unitCode,buildingName,category}],personMet,hasSignature,deviceId,latitude,longitude,gpsAccuracyM,atProperty})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/readings
  * Node Name: `http://127.0.0.1:5090/api/v1/readings ()({transactionId,meterId,condition,reasonCode,note,newReading,oldFinalReading,newMeterNumber,newOpeningReading,newCurrentReading,readerConfirmedWarning,capturedAtUtc,photoCount,subTenant,deviceId,latitude,longitude,gpsAccuracyM,tenantCode})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `400`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/readings
  * Node Name: `http://127.0.0.1:5090/api/v1/readings ()({transactionId,meterId,condition,reasonCode,note,newReading,oldFinalReading,newMeterNumber,newOpeningReading,newCurrentReading,readerConfirmedWarning,capturedAtUtc,photoCount,subTenant,deviceId,latitude,longitude,gpsAccuracyM,tenantCode})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/readings/
  * Node Name: `http://127.0.0.1:5090/api/v1/readings/ ()({transactionId,meterId,condition,reasonCode,note,newReading,oldFinalReading,newMeterNumber,newOpeningReading,newCurrentReading,readerConfirmedWarning,capturedAtUtc,photoCount,subTenant,deviceId,latitude,longitude,gpsAccuracyM,tenantCode})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/computeMetadata/v1/
  * Node Name: `http://127.0.0.1:5090/computeMetadata/v1/ ()({code,model,androidVersion,appVersion})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/latest/meta-data/
  * Node Name: `http://127.0.0.1:5090/latest/meta-data/ ()({code,model,androidVersion,appVersion})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/metadata/instance
  * Node Name: `http://127.0.0.1:5090/metadata/instance ()({code,model,androidVersion,appVersion})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/metadata/v1
  * Node Name: `http://127.0.0.1:5090/metadata/v1 ()({code,model,androidVersion,appVersion})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/opc/v1/instance/
  * Node Name: `http://127.0.0.1:5090/opc/v1/instance/ ()({code,model,androidVersion,appVersion})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/opc/v2/instance/
  * Node Name: `http://127.0.0.1:5090/opc/v2/instance/ ()({code,model,androidVersion,appVersion})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/openstack/latest/meta_data.json
  * Node Name: `http://127.0.0.1:5090/openstack/latest/meta_data.json ()({code,model,androidVersion,appVersion})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections/visitId/images/imageId%3Frole=role&result=result&capturedAtUtc=capturedAtUtc
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections/visitId/images/imageId (capturedAtUtc,result,role)`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections/visitId/images/imageId%3Frole=%2522&result=result&capturedAtUtc=capturedAtUtc
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections/visitId/images/imageId (capturedAtUtc,result,role)`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/inspections/visitId/images/imageId/
  * Node Name: `http://127.0.0.1:5090/api/v1/inspections/visitId/images/imageId/`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/readings/transactionId/images/imageId%3Frole=role&capturedAtUtc=capturedAtUtc
  * Node Name: `http://127.0.0.1:5090/api/v1/readings/transactionId/images/imageId (capturedAtUtc,role)`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `404`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/readings/transactionId/images/imageId%3Frole=role%2527&capturedAtUtc=capturedAtUtc
  * Node Name: `http://127.0.0.1:5090/api/v1/readings/transactionId/images/imageId (capturedAtUtc,role)`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/readings/transactionId/images/imageId/
  * Node Name: `http://127.0.0.1:5090/api/v1/readings/transactionId/images/imageId/`
  * Method: `PUT`
  * Parameter: ``
  * Attack: ``
  * Evidence: `429`
  * Other Info: ``


Instances: 96

### Solution



### Reference



#### CWE Id: [ 388 ](https://cwe.mitre.org/data/definitions/388.html)


#### WASC Id: 20

#### Source ID: 4

### [ Non-Storable Content ](https://www.zaproxy.org/docs/alerts/10049/)



##### Informational (Medium)

### Description

The response contents are not storable by caching components such as proxy servers. If the response does not contain sensitive, personal or user-specific information, it may benefit from being stored and cached, to improve performance.

* URL: http://127.0.0.1:5090/api/v1/me
  * Node Name: `http://127.0.0.1:5090/api/v1/me`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `no-store`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/properties/search%3Fq=q&zone=zone&done=done&type=type
  * Node Name: `http://127.0.0.1:5090/api/v1/properties/search (done,q,type,zone)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `no-store`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/readings/mine%3Fperiod=period
  * Node Name: `http://127.0.0.1:5090/api/v1/readings/mine (period)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `no-store`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/sync/meters%3Fzone=zone
  * Node Name: `http://127.0.0.1:5090/api/v1/sync/meters (zone)`
  * Method: `GET`
  * Parameter: ``
  * Attack: ``
  * Evidence: `no-store`
  * Other Info: ``
* URL: http://127.0.0.1:5090/api/v1/devices/register
  * Node Name: `http://127.0.0.1:5090/api/v1/devices/register ()({code,model,androidVersion,appVersion})`
  * Method: `POST`
  * Parameter: ``
  * Attack: ``
  * Evidence: `no-store`
  * Other Info: ``

Instances: Systemic


### Solution

The content may be marked as storable by ensuring that the following conditions are satisfied:
The request method must be understood by the cache and defined as being cacheable ("GET", "HEAD", and "POST" are currently defined as cacheable)
The response status code must be understood by the cache (one of the 1XX, 2XX, 3XX, 4XX, or 5XX response classes are generally understood)
The "no-store" cache directive must not appear in the request or response header fields
For caching by "shared" caches such as "proxy" caches, the "private" response directive must not appear in the response
For caching by "shared" caches such as "proxy" caches, the "Authorization" header field must not appear in the request, unless the response explicitly allows it (using one of the "must-revalidate", "public", or "s-maxage" Cache-Control response directives)
In addition to the conditions above, at least one of the following conditions must also be satisfied by the response:
It must contain an "Expires" header field
It must contain a "max-age" response directive
For "shared" caches such as "proxy" caches, it must contain a "s-maxage" response directive
It must contain a "Cache Control Extension" that allows it to be cached
It must have a status code that is defined as cacheable by default (200, 203, 204, 206, 300, 301, 404, 405, 410, 414, 501).

### Reference


* [ https://datatracker.ietf.org/doc/html/rfc7234 ](https://datatracker.ietf.org/doc/html/rfc7234)
* [ https://datatracker.ietf.org/doc/html/rfc7231 ](https://datatracker.ietf.org/doc/html/rfc7231)
* [ https://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html ](https://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html)


#### CWE Id: [ 524 ](https://cwe.mitre.org/data/definitions/524.html)


#### WASC Id: 13

#### Source ID: 3


