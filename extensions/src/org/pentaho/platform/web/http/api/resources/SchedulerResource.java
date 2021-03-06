/*
 * This program is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License, version 2.1 as published by the Free Software
 * Foundation.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this
 * program; if not, you can obtain a copy at http://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
 * or from the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * Copyright 2011 Pentaho Corporation.  All rights reserved.
 *
 *
 * @created 1/14/2011
 * @author Aaron Phillips
 *
 */
package org.pentaho.platform.web.http.api.resources;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.APPLICATION_XML;
import static javax.ws.rs.core.MediaType.TEXT_PLAIN;
import static javax.ws.rs.core.Response.Status.FORBIDDEN;
import static javax.ws.rs.core.Response.Status.UNAUTHORIZED;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pentaho.platform.api.action.IAction;
import org.pentaho.platform.api.engine.IAuthorizationPolicy;
import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.api.engine.IPluginManager;
import org.pentaho.platform.api.repository2.unified.IUnifiedRepository;
import org.pentaho.platform.api.repository2.unified.RepositoryFile;
import org.pentaho.platform.api.repository2.unified.UnifiedRepositoryException;
import org.pentaho.platform.api.scheduler2.IBlockoutManager;
import org.pentaho.platform.api.scheduler2.IJobFilter;
import org.pentaho.platform.api.scheduler2.IJobTrigger;
import org.pentaho.platform.api.scheduler2.IScheduler;
import org.pentaho.platform.api.scheduler2.Job;
import org.pentaho.platform.api.scheduler2.SchedulerException;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.engine.security.SecurityHelper;
import org.pentaho.platform.repository.RepositoryFilenameUtils;
import org.pentaho.platform.security.policy.rolebased.actions.AdministerSecurityAction;
import org.pentaho.platform.security.policy.rolebased.actions.SchedulerAction;

/**
 * Represents a file node in the repository. This api provides methods for discovering information about repository files as well as CRUD operations
 * 
 * @author aaron
 * 
 */
@Path("/scheduler")
public class SchedulerResource extends AbstractJaxRSResource {

  protected IScheduler scheduler = PentahoSystem.get(IScheduler.class, "IScheduler2", null); //$NON-NLS-1$

  protected IUnifiedRepository repository = PentahoSystem.get(IUnifiedRepository.class);

  protected IAuthorizationPolicy policy = PentahoSystem.get(IAuthorizationPolicy.class);

  IPluginManager pluginMgr = PentahoSystem.get(IPluginManager.class);

  private static final Log logger = LogFactory.getLog(SchedulerResource.class);

  public SchedulerResource() {
  }

  // ///////
  // CREATE
  // should this be changed to a PUT?
  @POST
  @Path("/job")
  @Consumes({ APPLICATION_JSON, APPLICATION_XML })
  @Produces("text/plain")
  public Response createJob(JobScheduleRequest scheduleRequest) throws IOException {

    // Used to determine if created by a RunInBackgroundCommand
    boolean runInBackground = scheduleRequest.getSimpleJobTrigger() == null
        && scheduleRequest.getComplexJobTrigger() == null && scheduleRequest.getCronJobTrigger() == null;

    boolean hasInputFile = !StringUtils.isEmpty(scheduleRequest.getInputFile());
    RepositoryFile file = null;
    if (hasInputFile) {
      try {
        file = repository.getFile(scheduleRequest.getInputFile());
      } catch (UnifiedRepositoryException ure) {
        hasInputFile = false;
        logger.warn(ure.getMessage(), ure);
      }
    }

    // if we have an inputfile, generate job name based on that if the name is not passed in
    if (hasInputFile && StringUtils.isEmpty(scheduleRequest.getJobName())) {
      scheduleRequest.setJobName(file.getName().substring(0, file.getName().lastIndexOf("."))); //$NON-NLS-1$
    } else if (!StringUtils.isEmpty(scheduleRequest.getActionClass())) {
      String actionClass = scheduleRequest.getActionClass().substring(
          scheduleRequest.getActionClass().lastIndexOf(".") + 1);
      scheduleRequest.setJobName(actionClass); //$NON-NLS-1$
    } else if (!hasInputFile && StringUtils.isEmpty(scheduleRequest.getJobName())) {
      // just make up a name
      scheduleRequest.setJobName("" + System.currentTimeMillis()); //$NON-NLS-1$
    }

    if (!runInBackground && !policy.isAllowed(SchedulerAction.NAME)) {
      return Response.status(UNAUTHORIZED).build();
    }

    if (hasInputFile) {
      Map<String, Serializable> metadata = repository.getFileMetadata(file.getId());
      if (metadata.containsKey("_PERM_SCHEDULABLE")) {
        boolean schedulable = Boolean.parseBoolean((String) metadata.get("_PERM_SCHEDULABLE"));
        if (!schedulable) {
          return Response.status(FORBIDDEN).build();
        }
      }
    }

    Job job = null;
    try {
      IJobTrigger jobTrigger = SchedulerResourceUtil.convertScheduleRequestToJobTrigger(scheduleRequest, scheduler);

      HashMap<String, Serializable> parameterMap = new HashMap<String, Serializable>();
      for (JobScheduleParam param : scheduleRequest.getJobParameters()) {
        parameterMap.put(param.getName(), param.getValue());
      }

      if (hasInputFile) {
        SchedulerOutputPathResolver outputPathResolver = new SchedulerOutputPathResolver(scheduleRequest);
        String outputFile = outputPathResolver.resolveOutputFilePath();
        String actionId = RepositoryFilenameUtils.getExtension(scheduleRequest.getInputFile()) + ".backgroundExecution"; //$NON-NLS-1$ //$NON-NLS-2$
        job = scheduler.createJob(scheduleRequest.getJobName(), actionId, parameterMap, jobTrigger,
            new RepositoryFileStreamProvider(scheduleRequest.getInputFile(), outputFile));
      } else {
        // need to locate actions from plugins if done this way too (but for now, we're just on main)
        String actionClass = scheduleRequest.getActionClass();
        try {
          @SuppressWarnings("unchecked")
          Class<IAction> iaction = ((Class<IAction>) Class.forName(actionClass));
          job = scheduler.createJob(scheduleRequest.getJobName(), iaction, parameterMap, jobTrigger);
        } catch (ClassNotFoundException e) {
          throw new RuntimeException(e);
        }
      }
    } catch (SchedulerException e) {
      return Response.serverError().entity(e.getCause().getMessage()).build();
    }
    return Response.ok(job.getJobId()).type(MediaType.TEXT_PLAIN).build();
  }

  @POST
  @Path("/triggerNow")
  @Produces("text/plain")
  @Consumes({ APPLICATION_XML, APPLICATION_JSON })
  public Response triggerNow(JobRequest jobRequest) {
    try {
      Job job = scheduler.getJob(jobRequest.getJobId());
      if (policy.isAllowed(SchedulerAction.NAME)) {
        scheduler.triggerNow(jobRequest.getJobId());
      } else {
        if (PentahoSessionHolder.getSession().getName().equals(job.getUserName())) {
          scheduler.triggerNow(jobRequest.getJobId());
        }
      }
      // udpate job state
      job = scheduler.getJob(jobRequest.getJobId());
      return Response.ok(job.getState().name()).type(MediaType.TEXT_PLAIN).build();
    } catch (SchedulerException e) {
      throw new RuntimeException(e);
    }
  }

  @GET
  @Path("/getContentCleanerJob")
  @Produces({ APPLICATION_JSON, APPLICATION_XML })
  public Job getContentCleanerJob() {
    try {
      IPentahoSession session = PentahoSessionHolder.getSession();
      final String principalName = session.getName(); // this authentication wasn't matching with the job user name, changed to get name via the current session
      final Boolean canAdminister = canAdminister(session);

      List<Job> jobs = scheduler.getJobs(new IJobFilter() {
        public boolean accept(Job job) {
          String actionClass = (String) job.getJobParams().get("ActionAdapterQuartzJob-ActionClass");
          if (canAdminister && "org.pentaho.platform.admin.GeneratedContentCleaner".equals(actionClass)) {
            return true;
          }
          return principalName.equals(job.getUserName())
              && "org.pentaho.platform.admin.GeneratedContentCleaner".equals(actionClass);
        }
      });

      if (jobs.size() > 0) {
        return jobs.get(0);
      }
      return null;
    } catch (SchedulerException e) {
      throw new RuntimeException(e);
    }
  }

  @GET
  @Path("/jobs")
  @Produces({ APPLICATION_JSON, APPLICATION_XML })
  public List<Job> getJobs(@DefaultValue("false")
  @QueryParam("asCronString")
  Boolean asCronString) {
    try {
      IPentahoSession session = PentahoSessionHolder.getSession();
      final String principalName = session.getName(); // this authentication wasn't matching with the job user name, changed to get name via the current session
      final Boolean canAdminister = canAdminister(session);

      List<Job> jobs = scheduler.getJobs(new IJobFilter() {
        public boolean accept(Job job) {
          if (canAdminister) {
            return !IBlockoutManager.BLOCK_OUT_JOB_NAME.equals(job.getJobName());
          }
          return principalName.equals(job.getUserName());
        }
      });
      return jobs;
    } catch (SchedulerException e) {
      throw new RuntimeException(e);
    }
  }

  private Boolean canAdminister(IPentahoSession session) {
    if (policy.isAllowed(AdministerSecurityAction.NAME)) {
      return true;
    }
    return false;
  }

  @GET
  @Path("/isScheduleAllowed")
  @Produces(TEXT_PLAIN)
  public String isScheduleAllowed(@QueryParam("id") String id) {
    Boolean canSchedule = false;
    canSchedule = policy.isAllowed(SchedulerAction.NAME);
    if (canSchedule) {
      Map<String, Serializable> metadata = repository.getFileMetadata(id);
      if (metadata.containsKey("_PERM_SCHEDULABLE")) {
        canSchedule = Boolean.parseBoolean((String) metadata.get("_PERM_SCHEDULABLE"));
      }
    }
    return "" + canSchedule; //$NON-NLS-1$
  }
  
  
  @GET
  @Path("/canSchedule")
  @Produces(TEXT_PLAIN)
  public String doGetCanSchedule() {
    Boolean isAllowed = policy.isAllowed(SchedulerAction.NAME);
    return isAllowed ? "true" : "false"; //$NON-NLS-1$//$NON-NLS-2$
  }

  @GET
  @Path("/state")
  @Produces("text/plain")
  public Response getState() {
    try {
      return Response.ok(scheduler.getStatus().name()).type(MediaType.TEXT_PLAIN).build();
    } catch (SchedulerException e) {
      throw new RuntimeException(e);
    }
  }

  @POST
  @Path("/start")
  @Produces("text/plain")
  public Response start() {
    try {
      if (policy.isAllowed(SchedulerAction.NAME)) {
        scheduler.start();
      }
      return Response.ok(scheduler.getStatus().name()).type(MediaType.TEXT_PLAIN).build();
    } catch (SchedulerException e) {
      throw new RuntimeException(e);
    }
  }

  @POST
  @Path("/pause")
  @Produces("text/plain")
  public Response pause() {
    try {
      if (policy.isAllowed(SchedulerAction.NAME)) {
        scheduler.pause();
      }
      return Response.ok(scheduler.getStatus().name()).type(MediaType.TEXT_PLAIN).build();
    } catch (SchedulerException e) {
      throw new RuntimeException(e);
    }
  }

  @POST
  @Path("/shutdown")
  @Produces("text/plain")
  public Response shutdown() {
    try {
      if (policy.isAllowed(SchedulerAction.NAME)) {
        scheduler.shutdown();
      }
      return Response.ok(scheduler.getStatus().name()).type(MediaType.TEXT_PLAIN).build();
    } catch (SchedulerException e) {
      throw new RuntimeException(e);
    }
  }

  @POST
  @Path("/jobState")
  @Produces("text/plain")
  @Consumes({ APPLICATION_XML, APPLICATION_JSON })
  public Response getJobState(JobRequest jobRequest) {
    try {
      Job job = scheduler.getJob(jobRequest.getJobId());
      if (policy.isAllowed(SchedulerAction.NAME)) {
        return Response.ok(job.getState().name()).type(MediaType.TEXT_PLAIN).build();
      } else {
        if (PentahoSessionHolder.getSession().getName().equals(job.getUserName())) {
          return Response.ok(job.getState().name()).type(MediaType.TEXT_PLAIN).build();
        }
      }
      return Response.status(Status.UNAUTHORIZED).type(MediaType.TEXT_PLAIN).build();
    } catch (SchedulerException e) {
      throw new RuntimeException(e);
    }
  }

  @POST
  @Path("/pauseJob")
  @Produces("text/plain")
  @Consumes({ APPLICATION_XML, APPLICATION_JSON })
  public Response pauseJob(JobRequest jobRequest) {
    try {
      Job job = scheduler.getJob(jobRequest.getJobId());
      if (policy.isAllowed(SchedulerAction.NAME)) {
        scheduler.pauseJob(jobRequest.getJobId());
      } else {
        if (PentahoSessionHolder.getSession().getName().equals(job.getUserName())) {
          scheduler.pauseJob(jobRequest.getJobId());
        }
      }
      // update job state
      job = scheduler.getJob(jobRequest.getJobId());
      return Response.ok(job.getState().name()).type(MediaType.TEXT_PLAIN).build();
    } catch (SchedulerException e) {
      throw new RuntimeException(e);
    }
  }

  @POST
  @Path("/resumeJob")
  @Produces("text/plain")
  @Consumes({ APPLICATION_XML, APPLICATION_JSON })
  public Response resumeJob(JobRequest jobRequest) {
    try {
      Job job = scheduler.getJob(jobRequest.getJobId());
      if (policy.isAllowed(SchedulerAction.NAME)) {
        scheduler.resumeJob(jobRequest.getJobId());
      } else {
        if (PentahoSessionHolder.getSession().getName().equals(job.getUserName())) {
          scheduler.resumeJob(jobRequest.getJobId());
        }
      }
      // udpate job state
      job = scheduler.getJob(jobRequest.getJobId());
      return Response.ok(job.getState().name()).type(MediaType.TEXT_PLAIN).build();
    } catch (SchedulerException e) {
      throw new RuntimeException(e);
    }
  }

  @DELETE
  @Path("/removeJob")
  @Produces("text/plain")
  @Consumes({ APPLICATION_XML, APPLICATION_JSON })
  public Response removeJob(JobRequest jobRequest) {
    try {
      Job job = scheduler.getJob(jobRequest.getJobId());
      if (policy.isAllowed(SchedulerAction.NAME)) {
        scheduler.removeJob(jobRequest.getJobId());
        return Response.ok("REMOVED").type(MediaType.TEXT_PLAIN).build();
      } else {
        if (PentahoSessionHolder.getSession().getName().equals(job.getUserName())) {
          scheduler.removeJob(jobRequest.getJobId());
          return Response.ok("REMOVED").type(MediaType.TEXT_PLAIN).build();
        }
      }
      return Response.ok(job.getState().name()).type(MediaType.TEXT_PLAIN).build();
    } catch (SchedulerException e) {
      throw new RuntimeException(e);
    }
  }

  @GET
  @Path("/jobinfo")
  @Produces({ APPLICATION_JSON, APPLICATION_XML })
  public Job getJob(@QueryParam("jobId")
  String jobId, @DefaultValue("false")
  @QueryParam("asCronString")
  String asCronString) {
    try {
      Job job = scheduler.getJob(jobId);
      if (SecurityHelper.getInstance().isPentahoAdministrator(PentahoSessionHolder.getSession())
          || PentahoSessionHolder.getSession().getName().equals(job.getUserName())) {
        for (String key : job.getJobParams().keySet()) {
          Serializable value = job.getJobParams().get(key);
          if (value.getClass().isArray()) {
            String[] sa = (new String[0]).getClass().cast(value);
            ArrayList<String> list = new ArrayList<String>();
            for (int i = 0; i < sa.length; i++) {
              list.add(sa[i]);
            }
            job.getJobParams().put(key, list);
          }
        }
        return job;
      } else {
        throw new RuntimeException("Job not found or improper credentials for access");
      }
    } catch (SchedulerException e) {
      throw new RuntimeException(e);
    }
  }

  @GET
  @Path("/jobinfotest")
  @Produces({ APPLICATION_JSON })
  public JobScheduleRequest getJobInfo() {
    JobScheduleRequest jobRequest = new JobScheduleRequest();
    ComplexJobTriggerProxy proxyTrigger = new ComplexJobTriggerProxy();
    proxyTrigger.setDaysOfMonth(new int[] { 1, 2, 3 });
    proxyTrigger.setDaysOfWeek(new int[] { 1, 2, 3 });
    proxyTrigger.setMonthsOfYear(new int[] { 1, 2, 3 });
    proxyTrigger.setYears(new int[] { 2012, 2013 });
    proxyTrigger.setStartTime(new Date());
    jobRequest.setComplexJobTrigger(proxyTrigger);
    jobRequest.setInputFile("aaaaa");
    jobRequest.setOutputFile("bbbbb");
    ArrayList<JobScheduleParam> jobParams = new ArrayList<JobScheduleParam>();
    jobParams.add(new JobScheduleParam("param1", "aString"));
    jobParams.add(new JobScheduleParam("param2", 1));
    jobParams.add(new JobScheduleParam("param3", true));
    jobParams.add(new JobScheduleParam("param4", new Date()));
    jobRequest.setJobParameters(jobParams);
    return jobRequest;
  }
}
