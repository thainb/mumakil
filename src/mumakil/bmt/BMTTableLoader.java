/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.nio.ByteBuffer;
import java.lang.NumberFormatException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.LinkedList;
import java.util.List;
import java.net.InetAddress;
import java.net.BindException;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.conf.*;
import org.apache.hadoop.util.*;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.mapred.TaskID;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.filter.QueryPath;
import org.apache.cassandra.net.IAsyncResult;
import org.apache.cassandra.net.Message;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.utils.FBUtilities;

/*

  First of all we expect a comma separated list of field names to be passed in via '-Dcassandra.col_names=...'. With this
  we read in lines from the hdfs path expecting that each record adheres to this schema. This record is inserted directly
  into cassandra, no thrift.

 */
public class BMTTableLoader extends Configured implements Tool {
    public static class Map extends MapReduceBase implements Mapper<LongWritable, Text, Text, Text> {
        
        private JobConf jobconf;
        private ColumnFamily columnFamily;
        private String keyspace;
        private String cfName;
        private Integer keyField;
        private Integer tsField;
        private String[] fieldNames;

        public void map(LongWritable key, Text value, OutputCollector<Text, Text> output, Reporter reporter) throws IOException {

            String[] fields = value.toString().split("\t");
            
            /* Handle custom timestamp for all columns */
            Long timeStamp;
            if (tsField == -1) {
                timeStamp = System.currentTimeMillis();
            } else {
                timeStamp = Long.parseLong(fields[tsField]);
            }

            /* Create linked list of column families, this will hold only one column family */            
            List<ColumnFamily> columnFamilyList = new LinkedList<ColumnFamily>();
            columnFamily = ColumnFamily.create(keyspace, cfName);
            
            for(int i = 0; i < fields.length; i++) {
                if (i < fieldNames.length && i != keyField) {
                    columnFamily.addColumn(new QueryPath(cfName, null, ByteBuffer.wrap(fieldNames[i].getBytes("UTF-8"))), ByteBuffer.wrap(fields[i].getBytes("UTF-8")), timeStamp);
                }
            }
            columnFamilyList.add(columnFamily);
            
            /* Serialize our data as a binary message and send it out to Cassandra endpoints */
            Message message = MemtableMessenger.createMessage(keyspace, fields[keyField].getBytes("UTF-8"), cfName, columnFamilyList);
            List<IAsyncResult> results = new ArrayList<IAsyncResult>();
            for (InetAddress endpoint: StorageService.instance.getNaturalEndpoints(keyspace, ByteBuffer.wrap(fields[keyField].getBytes()))) {
              results.add(MessagingService.instance.sendRR(message, endpoint));
            }
        }

        /*
         *  Called at very beginning of map task, sets up basic configuration at the task level
         */
        public void configure(JobConf job) {
            
            this.jobconf      = job;
            this.keyspace     = job.get("cassandra.keyspace");
            this.cfName       = job.get("cassandra.column_family");
            this.fieldNames   = job.get("cassandra.col_names").split(",");
            this.keyField     = Integer.parseInt(job.get("cassandra.row_key_field"));

            /* Deal with custom timestamp field */
            try {
                this.tsField = Integer.parseInt(job.get("cassandra.timestamp_field"));
            } catch (NumberFormatException e) {
                this.tsField = -1;
            }

            System.setProperty("cassandra.config", job.get("cassandra.config"));
            
            try {
              CassandraStorageClient.init();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            try {
                Thread.sleep(10*1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        public void close() {
          CassandraStorageClient.close();
        }
    }

    /*
     *  Interprets commandline args, sets configuration, and actually runs the job
     */
    public int run(String[] args) {
        JobConf conf                = new JobConf(getConf(), BMTTableLoader.class);
        GenericOptionsParser parser = new GenericOptionsParser(conf,args);

        conf.setJobName("BMTTableLoader");
        conf.setMapperClass(Map.class);
        conf.setNumReduceTasks(0);
        conf.setOutputKeyClass(Text.class);
        conf.setOutputValueClass(Text.class);

        List<String> other_args = new ArrayList<String>();
        for (int i=0; i < args.length; ++i) {
            other_args.add(args[i]);
        }
	
        FileInputFormat.setInputPaths(conf, new Path(other_args.get(0)));
        FileOutputFormat.setOutputPath(conf, new Path(other_args.get(1)));
        try {
            JobClient.runJob(conf);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
        return 0;
    }

   /*
    *  Main class, simply shells out to generic tool runner for Hadoop. This
    *  means you can pass command line script the usual options with '-D, -libjars'
    *  for free.
    */
    public static void main(String[] args) throws Exception {
        int res = ToolRunner.run(new Configuration(), new BMTTableLoader(), args);
        System.exit(res);
    }
}
