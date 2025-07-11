---
title: "Kafka Connect"
---
<!--
 - Licensed to the Apache Software Foundation (ASF) under one or more
 - contributor license agreements.  See the NOTICE file distributed with
 - this work for additional information regarding copyright ownership.
 - The ASF licenses this file to You under the Apache License, Version 2.0
 - (the "License"); you may not use this file except in compliance with
 - the License.  You may obtain a copy of the License at
 -
 -   http://www.apache.org/licenses/LICENSE-2.0
 -
 - Unless required by applicable law or agreed to in writing, software
 - distributed under the License is distributed on an "AS IS" BASIS,
 - WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 - See the License for the specific language governing permissions and
 - limitations under the License.
 -->

# Kafka Connect

[Kafka Connect](https://docs.confluent.io/platform/current/connect/index.html) is a popular framework for moving data
in and out of Kafka via connectors. There are many different connectors available, such as the S3 sink
for writing data from Kafka to S3 and Debezium source connectors for writing change data capture records from relational
databases to Kafka.

It has a straightforward, decentralized, distributed architecture. A cluster consists of a number of worker processes,
and a connector runs tasks on these processes to perform the work. Connector deployment is configuration driven, so
generally no code needs to be written to run a connector.

## Apache Iceberg Sink Connector

The Apache Iceberg Sink Connector for Kafka Connect is a sink connector for writing data from Kafka into Iceberg tables.

## Features

* Commit coordination for centralized Iceberg commits
* Exactly-once delivery semantics
* Multi-table fan-out
* Automatic table creation and schema evolution
* Field name mapping via Iceberg’s column mapping functionality

## Installation

The connector zip archive is created as part of the Iceberg build. You can run the build via:
```bash
./gradlew -x test -x integrationTest clean build
```
The zip archive will be found under `./kafka-connect/kafka-connect-runtime/build/distributions`. There is
one distribution that bundles the Hive Metastore client and related dependencies, and one that does not.
Copy the distribution archive into the Kafka Connect plugins directory on all nodes.

## Requirements

The sink relies on [KIP-447](https://cwiki.apache.org/confluence/display/KAFKA/KIP-447%3A+Producer+scalability+for+exactly+once+semantics)
for exactly-once semantics. This requires Kafka 2.5 or later.

## Configuration

| Property                                   | Description                                                                                                      |
|--------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| iceberg.tables                             | Comma-separated list of destination tables                                                                       |
| iceberg.tables.dynamic-enabled             | Set to `true` to route to a table specified in `routeField` instead of using `routeRegex`, default is `false`    |
| iceberg.tables.route-field                 | For multi-table fan-out, the name of the field used to route records to tables                                   |
| iceberg.tables.default-commit-branch       | Default branch for commits, main is used if not specified                                                        |
| iceberg.tables.default-id-columns          | Default comma-separated list of columns that identify a row in tables (primary key)                              |
| iceberg.tables.default-partition-by        | Default comma-separated list of partition field names to use when creating tables                                |
| iceberg.tables.auto-create-enabled         | Set to `true` to automatically create destination tables, default is `false`                                     |
| iceberg.tables.evolve-schema-enabled       | Set to `true` to add any missing record fields to the table schema, default is `false`                           |
| iceberg.tables.schema-force-optional       | Set to `true` to set columns as optional during table create and evolution, default is `false` to respect schema |
| iceberg.tables.schema-case-insensitive     | Set to `true` to look up table columns by case-insensitive name, default is `false` for case-sensitive           |
| iceberg.tables.auto-create-props.*         | Properties set on new tables during auto-create                                                                  |
| iceberg.tables.write-props.*               | Properties passed through to Iceberg writer initialization, these take precedence                                |
| iceberg.table.<_table-name_\>.commit-branch | Table-specific branch for commits, use `iceberg.tables.default-commit-branch` if not specified                   |
| iceberg.table.<_table-name_\>.id-columns    | Comma-separated list of columns that identify a row in the table (primary key)                                   |
| iceberg.table.<_table-name_\>.partition-by  | Comma-separated list of partition fields to use when creating the table                                          |
| iceberg.table.<_table-name_\>.route-regex   | The regex used to match a record's `routeField` to a table                                                       |
| iceberg.control.topic                      | Name of the control topic, default is `control-iceberg`                                                          |
| iceberg.control.group-id-prefix            | Prefix for the control consumer group, default is `cg-control`                                                   |
| iceberg.control.commit.interval-ms         | Commit interval in msec, default is 300,000 (5 min)                                                              |
| iceberg.control.commit.timeout-ms          | Commit timeout interval in msec, default is 30,000 (30 sec)                                                      |
| iceberg.control.commit.threads             | Number of threads to use for commits, default is (cores * 2)                                                     |
| iceberg.coordinator.transactional.prefix   | Prefix for the transactional id to use for the coordinator producer, default is to use no/empty prefix           |
| iceberg.catalog                            | Name of the catalog, default is `iceberg`                                                                        |
| iceberg.catalog.*                          | Properties passed through to Iceberg catalog initialization                                                      |
| iceberg.hadoop-conf-dir                    | If specified, Hadoop config files in this directory will be loaded                                               |
| iceberg.hadoop.*                           | Properties passed through to the Hadoop configuration                                                            |
| iceberg.kafka.*                            | Properties passed through to control topic Kafka client initialization                                           |

If `iceberg.tables.dynamic-enabled` is `false` (the default) then you must specify `iceberg.tables`. If
`iceberg.tables.dynamic-enabled` is `true` then you must specify `iceberg.tables.route-field` which will
contain the name of the table.

### Kafka configuration

By default the connector will attempt to use Kafka client config from the worker properties for connecting to
the control topic. If that config cannot be read for some reason, Kafka client settings
can be set explicitly using `iceberg.kafka.*` properties.

#### Message format

Messages should be converted to a struct or map using the appropriate Kafka Connect converter.

### Catalog configuration

The `iceberg.catalog.*` properties are required for connecting to the Iceberg catalog. The core catalog
types are included in the default distribution, including REST, Glue, DynamoDB, Hadoop, Nessie,
JDBC, Hive and BigQuery Metastore. JDBC drivers are not included in the default distribution, so you will need to include
those if needed. When using a Hive catalog, you can use the distribution that includes the Hive metastore client,
otherwise you will need to include that yourself.

To set the catalog type, you can set `iceberg.catalog.type` to `rest`, `hive`, or `hadoop`. For other
catalog types, you need to instead set `iceberg.catalog.catalog-impl` to the name of the catalog class.

#### REST example

```
"iceberg.catalog.type": "rest",
"iceberg.catalog.uri": "https://catalog-service",
"iceberg.catalog.credential": "<credential>",
"iceberg.catalog.warehouse": "<warehouse>",
```

#### Hive example

NOTE: Use the distribution that includes the HMS client (or include the HMS client yourself). Use `S3FileIO` when
using S3 for storage and `GCSFileIO` when using GCS (the default is `HadoopFileIO` with `HiveCatalog`).
```
"iceberg.catalog.type": "hive",
"iceberg.catalog.uri": "thrift://hive:9083",
"iceberg.catalog.io-impl": "org.apache.iceberg.aws.s3.S3FileIO",
"iceberg.catalog.warehouse": "s3a://bucket/warehouse",
"iceberg.catalog.client.region": "us-east-1",
"iceberg.catalog.s3.access-key-id": "<AWS access>",
"iceberg.catalog.s3.secret-access-key": "<AWS secret>",
```

#### Glue example

```
"iceberg.catalog.catalog-impl": "org.apache.iceberg.aws.glue.GlueCatalog",
"iceberg.catalog.warehouse": "s3a://bucket/warehouse",
"iceberg.catalog.io-impl": "org.apache.iceberg.aws.s3.S3FileIO",
```

#### Nessie example

```
"iceberg.catalog.catalog-impl": "org.apache.iceberg.nessie.NessieCatalog",
"iceberg.catalog.uri": "http://localhost:19120/api/v2",
"iceberg.catalog.ref": "main",
"iceberg.catalog.warehouse": "s3a://bucket/warehouse",
"iceberg.catalog.io-impl": "org.apache.iceberg.aws.s3.S3FileIO",
```

#### BigQuery Metastore example

```
"iceberg.catalog.catalog-impl": "org.apache.iceberg.gcp.bigquery.BigQueryMetastoreCatalog",
"iceberg.catalog.gcp.bigquery.project-id": "my-project",
"iceberg.catalog.gcp.bigquery.location": "us-east1",
"iceberg.catalog.warehouse": "gs://bucket/warehouse",
"iceberg.catalog.io-impl": "org.apache.iceberg.gcp.gcs.GCSFileIO",
"iceberg.tables.auto-create-props.bq_connection": "projects/my-project/locations/us-east1/connections/my-connection",
```

#### Notes

Depending on your setup, you may need to also set `iceberg.catalog.s3.endpoint`, `iceberg.catalog.s3.staging-dir`,
or `iceberg.catalog.s3.path-style-access`. See the [Iceberg docs](https://iceberg.apache.org/docs/latest/) for
full details on configuring catalogs.

### Azure ADLS configuration example

When using ADLS, Azure requires the passing of AZURE_CLIENT_ID, AZURE_TENANT_ID, and AZURE_CLIENT_SECRET for its Java SDK.
If you're running Kafka Connect in a container, be sure to inject those values as environment variables. See the
[Azure Identity Client library for Java](https://learn.microsoft.com/en-us/java/api/overview/azure/identity-readme?view=azure-java-stable) for more information.

An example of these would be:
```
AZURE_CLIENT_ID=e564f687-7b89-4b48-80b8-111111111111
AZURE_TENANT_ID=95f2f365-f5b7-44b1-88a1-111111111111
AZURE_CLIENT_SECRET="XXX"
```
Where the CLIENT_ID is the Application ID of a registered application under
[App Registrations](https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade), the TENANT_ID is
from your [Azure Tenant Properties](https://portal.azure.com/#view/Microsoft_AAD_IAM/TenantProperties.ReactView), and
the CLIENT_SECRET is created within the "Certificates & Secrets" section, under "Manage" after choosing your specific
App Registration. You might have to choose "Client secrets" in the middle panel and the "+" in front of "New client secret"
to generate one. Be sure to set this variable to the Value and not the Id.

It's also important that the App Registration is granted the Role Assignment "Storage Blob Data Contributor" in your
Storage Account's Access Control (IAM), or it won't be able to write new files there.

Then, within the Connector's configuration, you'll want to include the following:

```
"iceberg.catalog.type": "rest",
"iceberg.catalog.uri": "https://catalog:8181",
"iceberg.catalog.warehouse": "abfss://storage-container-name@storageaccount.dfs.core.windows.net/warehouse",
"iceberg.catalog.io-impl": "org.apache.iceberg.azure.adlsv2.ADLSFileIO",
"iceberg.catalog.include-credentials": "true"
```

Where `storage-container-name` is the container name within your Azure Storage Account, `/warehouse` is the location
within that container where your Apache Iceberg files will be written by default (or if iceberg.tables.auto-create-enabled=true),
and the `include-credentials` parameter passes along the Azure Java client credentials along. This will configure the
Iceberg Sink connector to connect to the REST catalog implementation at `iceberg.catalog.uri` to obtain the required
Connection String for the ADLSv2 client

### Google GCS configuration example

By default, Application Default Credentials (ADC) will be used to connect to GCS. Details on how ADC works can
be found in the [Google Cloud documentation](https://cloud.google.com/docs/authentication/application-default-credentials).

```
"iceberg.catalog.type": "rest",
"iceberg.catalog.uri": "https://catalog:8181",
"iceberg.catalog.warehouse": "gs://bucket-name/warehouse",
"iceberg.catalog.io-impl": "org.apache.iceberg.google.gcs.GCSFileIO"
```

### Hadoop configuration

When using HDFS or Hive, the sink will initialize the Hadoop configuration. First, config files
from the classpath are loaded. Next, if `iceberg.hadoop-conf-dir` is specified, config files
are loaded from that location. Finally, any `iceberg.hadoop.*` properties from the sink config are
applied. When merging these, the order of precedence is sink config > config dir > classpath.

## Examples

### Initial setup

#### Source topic

This assumes the source topic already exists and is named `events`.

#### Control topic

If your Kafka cluster has `auto.create.topics.enable` set to `true` (the default), then the control topic will be
automatically created. If not, then you will need to create the topic first. The default topic name is `control-iceberg`:
```bash
bin/kafka-topics  \
  --command-config command-config.props \
  --bootstrap-server ${CONNECT_BOOTSTRAP_SERVERS} \
  --create \
  --topic control-iceberg \
  --partitions 1
```
*NOTE: Clusters running on Confluent Cloud have `auto.create.topics.enable` set to `false` by default.*

#### Iceberg catalog configuration

Configuration properties with the prefix `iceberg.catalog.` will be passed to Iceberg catalog initialization.
See the [Iceberg docs](https://iceberg.apache.org/docs/latest/) for details on how to configure
a particular catalog.

### Single destination table

This example writes all incoming records to a single table.

#### Create the destination table

```sql
CREATE TABLE default.events (
    id STRING,
    type STRING,
    ts TIMESTAMP,
    payload STRING)
PARTITIONED BY (hours(ts))
```

#### Connector config

This example config connects to a Iceberg REST catalog.
```json
{
"name": "events-sink",
"config": {
    "connector.class": "org.apache.iceberg.connect.IcebergSinkConnector",
    "tasks.max": "2",
    "topics": "events",
    "iceberg.tables": "default.events",
    "iceberg.catalog.type": "rest",
    "iceberg.catalog.uri": "https://localhost",
    "iceberg.catalog.credential": "<credential>",
    "iceberg.catalog.warehouse": "<warehouse name>"
    }
}
```

### Multi-table fan-out, static routing

This example writes records with `type` set to `list` to the table `default.events_list`, and
writes records with `type` set to `create` to the table `default.events_create`. Other records
will be skipped.

#### Create two destination tables

```sql
CREATE TABLE default.events_list (
    id STRING,
    type STRING,
    ts TIMESTAMP,
    payload STRING)
PARTITIONED BY (hours(ts));

CREATE TABLE default.events_create (
    id STRING,
    type STRING,
    ts TIMESTAMP,
    payload STRING)
PARTITIONED BY (hours(ts));
```

#### Connector config

```json
{
"name": "events-sink",
"config": {
    "connector.class": "org.apache.iceberg.connect.IcebergSinkConnector",
    "tasks.max": "2",
    "topics": "events",
    "iceberg.tables": "default.events_list,default.events_create",
    "iceberg.tables.route-field": "type",
    "iceberg.table.default.events_list.route-regex": "list",
    "iceberg.table.default.events_create.route-regex": "create",
    "iceberg.catalog.type": "rest",
    "iceberg.catalog.uri": "https://localhost",
    "iceberg.catalog.credential": "<credential>",
    "iceberg.catalog.warehouse": "<warehouse name>"
    }
}
```

### Multi-table fan-out, dynamic routing

This example writes to tables with names from the value in the `db_table` field. If a table with
the name does not exist, then the record will be skipped. For example, if the record's `db_table`
field is set to `default.events_list`, then the record is written to the `default.events_list` table.

#### Create two destination tables

See above for creating two tables.

#### Connector config

```json
{
"name": "events-sink",
"config": {
    "connector.class": "org.apache.iceberg.connect.IcebergSinkConnector",
    "tasks.max": "2",
    "topics": "events",
    "iceberg.tables.dynamic-enabled": "true",
    "iceberg.tables.route-field": "db_table",
    "iceberg.catalog.type": "rest",
    "iceberg.catalog.uri": "https://localhost",
    "iceberg.catalog.credential": "<credential>",
    "iceberg.catalog.warehouse": "<warehouse name>"
    }
}
```

## SMTs for the Apache Iceberg Sink Connector

This project contains some SMTs that could be useful when transforming Kafka data for use by
the Iceberg sink connector.

### CopyValue
_(Experimental)_

The `CopyValue` SMT copies a value from one field to a new field.

#### Configuration

| Property         | Description       |
|------------------|-------------------|
| source.field     | Source field name |
| target.field     | Target field name |

#### Example

```
"transforms": "copyId",
"transforms.copyId.type": "org.apache.iceberg.connect.transforms.CopyValue",
"transforms.copyId.source.field": "id",
"transforms.copyId.target.field": "id_copy",
```

### DmsTransform
_(Experimental)_

The `DmsTransform` SMT transforms an AWS DMS formatted message for use by the sink's CDC feature.
It will promote the `data` element fields to top level and add the following metadata fields:
`_cdc.op`, `_cdc.ts`, and `_cdc.source`.

##### Configuration

The SMT currently has no configuration.

### DebeziumTransform
_(Experimental)_

The `DebeziumTransform` SMT transforms a Debezium formatted message for use by the sink's CDC feature.
It will promote the `before` or `after` element fields to top level and add the following metadata fields:
`_cdc.op`, `_cdc.ts`, `_cdc.offset`, `_cdc.source`, `_cdc.target`, and `_cdc.key`.

##### Configuration

| Property            | Description                                                                       |
|---------------------|-----------------------------------------------------------------------------------|
| cdc.target.pattern  | Pattern to use for setting the CDC target field value, default is `{db}.{table}`  |

### JsonToMapTransform
_(Experimental)_

The `JsonToMapTransform` SMT parses Strings as Json object payloads to infer schemas.  The iceberg-kafka-connect
connector for schema-less data (e.g. the Map produced by the Kafka supplied JsonConverter) is to convert Maps into Iceberg
Structs.  This is fine when the JSON is well-structured, but when you have JSON objects with dynamically
changing keys, it will lead to an explosion of columns in the Iceberg table due to schema evolutions.

This SMT is useful in situations where the JSON is not well-structured, in order to get data into Iceberg where
it can be further processed by query engines into a more manageable form. It will convert nested objects to
Maps and include Map type in the Schema.  The connector will respect the Schema and create Iceberg tables with Iceberg
Map (String) columns for the JSON objects.

Note:

- You must use the `stringConverter` as the `value.converter` setting for your connector, not `jsonConverter`
    - It expects JSON objects (`{...}`) in those strings.
- Message keys, tombstones, and headers are not transformed and are passed along as-is by the SMT

##### Configuration

| Property             | Description  (default value)             |
|----------------------|------------------------------------------|
| json.root | (false) Boolean value to start at root   |

The `transforms.IDENTIFIER_HERE.json.root` is meant for the most inconsistent data.  It will construct a Struct with a single field
called `payload` with a Schema of `Map<String, String>`.

If `transforms.IDENTIFIER_HERE.json.root` is false (the default), it will construct a Struct with inferred schemas for primitive and
array fields.  Nested objects become fields of type `Map<String, String>`.

Keys with empty arrays and empty objects are filtered out from the final schema.  Arrays will be typed unless the
json arrays have mixed types in which case they are converted to arrays of strings.

Example json:

```json
{
  "key": 1, 
  "array": [1,"two",3],
  "empty_obj": {},
  "nested_obj": {"some_key": ["one", "two"]}
}
```

Will become the following if `json.root` is true:

```
SinkRecord.schema: 
  "payload" : (Optional) Map<String, String>
  
Sinkrecord.value (Struct): 
  "payload"  : Map(
    "key" : "1",
    "array" : "[1,"two",3]"
    "empty_obj": "{}"
    "nested_obj": "{"some_key":["one","two"]}}"
   )
```

Will become the following if `json.root` is false

```
SinkRecord.schema: 
  "key": (Optional) Int32,
  "array": (Optional) Array<String>,
  "nested_object": (Optional) Map<string, String>
  
SinkRecord.value (Struct):
 "key" 1, 
 "array" ["1", "two", "3"] 
 "nested_object" Map ("some_key" : "["one", "two"]") 
```

### KafkaMetadataTransform
_(Experimental)_

The `KafkaMetadata` injects `topic`, `partition`, `offset`, `timestamp` which are properties are the Kafka message.

#### Configuration

| Property       | Description (default value)                                                       |
|----------------|-----------------------------------------------------------------------------------|
| field_name     | (_kafka_metadata) prefix for fields                                               | 
| nested         | (false) if true, nests data on a struct else adds to top level as prefixed fields |
| external_field | (none) appends a constant `key,value` to the metadata (e.g. cluster name)         | 

If `nested` is on:

`_kafka_metadata.topic`, `_kafka_metadata.partition`, `_kafka_metadata.offset`, `_kafka_metadata.timestamp`

If `nested` is off:
`_kafka_metdata_topic`, `_kafka_metadata_partition`, `_kafka_metadata_offset`, `_kafka_metadata_timestamp`

### MongoDebeziumTransform
_(Experimental)_

The `MongoDebeziumTransform` SMT transforms a Mongo Debezium formatted message with `before`/`after` BSON
strings into `before`/`after` typed Structs that the `DebeziumTransform` SMT expects.

It does not (yet) support renaming columns if mongodb column is not supported by your underlying
catalog type.

#### Configuration

| Property            | Description                                      |
|---------------------|--------------------------------------------------|
| array_handling_mode  | `array` or `document` to set array handling mode |

Value array (the default) will encode arrays as the array datatype. It is user’s responsibility to ensure that
all elements for a given array instance are of the same type. This option is a restricting one but offers
easy processing of arrays by downstream clients.

Value document will convert the array into a struct of structs in the similar way as done by BSON serialization.
The main struct contains fields named _0, _1, _2 etc. where the name represents the index of the element in the array.
Every element is then passed as the value for the given field.