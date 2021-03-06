/*
 * Copyright (c) 2013-2018, Centre for Genomic Regulation (CRG).
 * Copyright (c) 2013-2018, Paolo Di Tommaso and the respective authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.executor
import java.nio.file.Path

import groovy.util.logging.Slf4j
import nextflow.processor.TaskRun


/**
 * Implements a executor for OAR cluster
 *
 * See http://oar.imag.fr
 */
@Slf4j
class OarExecutor extends AbstractGridExecutor {

    /**
     * Gets the directives to submit the specified task to the cluster for execution
     *
     * @param task A {@link TaskRun} to be submitted
     * @param result The {@link List} instance to which add the job directives
     * @return A {@link List} containing all directive tokens and values.
     */

    protected List<String> getDirectives( TaskRun task, List<String> result ) {
        assert result !=null

        result << '-n' << getJobNameFor(task)
        result << '-d' << quote(task.workDir)

        // the requested queue name
        if( task.config.queue ) {
            result << '-q'  << (String)task.config.queue
        }

        // miscellaneous cluster options semicollon separated
        if( task.config.clusterOptions ) {
          for (String item : task.config.clusterOptions.toString().tokenize(';')) {
            result << item << ''
          }
        }

      return result

    }

    @Override
    String getHeaders( TaskRun task ) {
        String result = super.getHeaders(task)
        result += "cd ${quote(task.workDir)}\n"
        return result
    }

    @Override
    String getJobNameFor( TaskRun task ) {
        def result = super.getJobNameFor(task)
        // some implementations do not allow parenthesis in the job name -- see #271
        result = result.replace('(','').replace(')','')
        // PBS does not allow more than 15 characters for the job name string
        result && result.size()>15 ? result.substring(0,15) : result
    }
    /**
     * The command line to submit this job
     *
     * @param task The {@link TaskRun} instance to submit for execution to the cluster
     * @param scriptFile The file containing the job launcher script
     * @return A list representing the submit command line
     */
    List<String> getSubmitCommandLine(TaskRun task, Path scriptFile ) {
        scriptFile.setPermissions(7,0,0)
        return [ "oarsub", "-S", "./${scriptFile.getName()}"]
    }

    protected String getHeaderToken() { '#OAR' }

    /**
     * Parse the string returned by the {@code qsub} command and extract the job ID string
     *
     * @param text The string returned when submitting the job
     * @return The actual job ID string
     */

    @Override
    def parseJobId(String text) {
    def pattern = ~ /OAR_JOB_ID=(\d+)/
    for( String line : text.readLines() ) {
        def m = pattern.matcher(line)
        if( m.matches() ) {
            return m.group(1).toString()
            }
        }

        throw new IllegalStateException("Invalid OAR submit response:\n$text\n\n")
    }

    @Override
    protected List<String> getKillCommand() { ['oardel'] }

    @Override
    protected List<String> queueStatusCommand(Object queue) {
        String cmd = 'oarstat -f'
        if( queue ) cmd += ' ' + queue
        return ['sh','-c', "$cmd | egrep '(Job_Id:|state =)' ".toString()]
    }

    static private Map DECODE_STATUS = [
            'Terminated': QueueStatus.DONE,
            'Running': QueueStatus.RUNNING,
            'Waiting': QueueStatus.PENDING,
            'Suspended': QueueStatus.HOLD
    ]

    @Override
    protected Map<String, QueueStatus> parseQueueStatus(String text) {

        final JOB_ID = 'Job_Id:'
        final JOB_STATUS = 'state ='
        final result = [:]

        String id = null
        String status = null
        text.eachLine { line ->
            if( line.startsWith(JOB_ID) ) {
                id = fetchValue(JOB_ID, line)
            }
            else if( id ) {
                status = fetchValue(JOB_STATUS, line)
            }
            result.put( id, DECODE_STATUS[status] ?: AbstractGridExecutor.QueueStatus.UNKNOWN )
        }

        return result
    }

    static String fetchValue( String prefix, String line ) {
        final p = line.indexOf(prefix)
        return p!=-1 ? line.substring(p+prefix.size()).trim() : null
    }

}
