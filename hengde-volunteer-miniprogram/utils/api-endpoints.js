const ROLE_PREFIX = {
  volunteer: "/v",
  admin: "/a",
  enterprise: "/e"
};

const ENDPOINTS = {
  volunteer: {
    home: "/v/home",
    search: "/v/search",
    files: {
      upload: "/v/files/upload",
      profileImage: "/v/files/profile-image"
    },
    auth: {
      smsCodes: "/v/auth/sms/codes",
      wechatLogin: "/v/auth/login/wechat",
      devLogin: "/v/auth/login/dev",
      smsLogin: "/v/auth/login/sms",
      passwordLogin: "/v/auth/login/password",
      passwordChange: "/v/auth/password",
      passwordReset: "/v/auth/password/reset",
      agreement: "/v/auth/agreement",
      register: "/v/auth/register",
      wechatGroupMembership: "/v/auth/wechat/group-membership",
      myPermissions: "/v/auth/my-permissions",
      logout: "/v/auth/logout"
    },
    user: {
      profile: "/v/user/profile",
      phone: "/v/user/phone",
      volunteerCard: "/v/user/volunteer-card"
    },
    activity: {
      activities: "/v/activity/activities",
      activityDetail: (id) => `/v/activity/activities/${id}`,
      enroll: (id) => `/v/activity/activities/${id}/enroll`,
      cancelEnroll: (id) => `/v/activity/activities/${id}/enroll`,
      proxyEnrollments: (id) => `/v/activity/activities/${id}/proxy-enrollments`,
      myEnrollments: "/v/activity/my-enrollments",
      myActivities: "/v/activity/my-activities",
      myActivityDetail: (id) => `/v/activity/my-activities/${id}`,
      checkIn: (id) => `/v/activity/activities/${id}/check-in`,
      confirmHome: (id) => `/v/activity/activities/${id}/confirm-home`,
      review: (id) => `/v/activity/activities/${id}/review`,
      serviceRecords: "/v/activity/service-records",
      managedActivities: "/v/activity/managed-activities",
      managedActivityDetail: (id) => `/v/activity/managed-activities/${id}`,
      managedStart: (id) => `/v/activity/managed-activities/${id}/start`,
      managedFinish: (id) => `/v/activity/managed-activities/${id}/finish`,
      managedCheckOuts: (id) => `/v/activity/managed-activities/${id}/check-outs`,
      managedAttendance: (id, volunteerId) => `/v/activity/managed-activities/${id}/attendances/${volunteerId}`,
      managedViolation: (id, volunteerId) => `/v/activity/managed-activities/${id}/attendances/${volunteerId}/violations`,
      managedEvaluation: (id, volunteerId) => `/v/activity/managed-activities/${id}/attendances/${volunteerId}/evaluation`,
      managedSummary: (id) => `/v/activity/managed-activities/${id}/summary`,
      messages: (id) => `/v/activity/activities/${id}/messages`
    },
    organization: {
      groups: "/v/organization/groups",
      groupDetail: (id) => `/v/organization/groups/${id}`,
      groupJoin: (id) => `/v/organization/groups/${id}/join`,
      groupLeave: (id) => `/v/organization/groups/${id}/leave`,
      groupMembers: (id) => `/v/organization/groups/${id}/members`,
      groupJoinApplications: (id) => `/v/organization/groups/${id}/join-applications`,
      groupMemberApprove: (id, memberId) => `/v/organization/groups/${id}/members/${memberId}/approve`,
      groupMemberReject: (id, memberId) => `/v/organization/groups/${id}/members/${memberId}/reject`,
      groupMemberDelete: (id, memberId) => `/v/organization/groups/${id}/members/${memberId}`,
      groupMemberAdmin: (id, memberId) => `/v/organization/groups/${id}/members/${memberId}/admin`,
      squads: "/v/organization/squads",
      squadDetail: (id) => `/v/organization/squads/${id}`,
      squadApplications: (id) => `/v/organization/squads/${id}/applications`,
      structure: "/v/organization/structure",
      myPermissions: "/v/organization/my-permissions"
    },
    publicity: {
      banners: "/v/publicity/banners",
      announcements: "/v/publicity/announcements",
      announcementDetail: (id) => `/v/publicity/announcements/${id}`,
      files: "/v/publicity/files"
    },
    data: {
      dashboard: "/v/data/dashboard"
    }
  },
  admin: {
    auth: {
      login: "/a/auth/login",
      smsCodes: "/a/auth/sms/codes",
      passwordReset: "/a/auth/password/reset",
      password: "/a/auth/password",
      logout: "/a/auth/logout"
    },
    user: {
      volunteers: "/a/user/volunteers",
      volunteerDetail: (id) => `/a/user/volunteers/${id}`,
      volunteerStatus: (id) => `/a/user/volunteers/${id}/status`,
      volunteerPasswordReset: (id) => `/a/user/volunteers/${id}/password/reset`,
      volunteersExport: "/a/user/volunteers/export"
    },
    activity: {
      activities: "/a/activity/activities",
      activityDetail: (id) => `/a/activity/activities/${id}`,
      activityUpdate: (id) => `/a/activity/activities/${id}`,
      activityDelete: (id) => `/a/activity/activities/${id}`,
      copy: (id) => `/a/activity/activities/${id}/copy`,
      enrollments: (id) => `/a/activity/activities/${id}/enrollments`,
      enrollmentCreate: (id) => `/a/activity/activities/${id}/enrollments`,
      enrollmentsExport: (id) => `/a/activity/activities/${id}/enrollments/export`,
      enrollmentApprove: (id) => `/a/activity/enrollments/${id}/approve`,
      enrollmentReject: (id) => `/a/activity/enrollments/${id}/reject`,
      enrollmentDelete: (id) => `/a/activity/enrollments/${id}`,
      leaders: (id) => `/a/activity/activities/${id}/leaders`,
      leaderDelete: (id, leaderId) => `/a/activity/activities/${id}/leaders/${leaderId}`,
      start: (id) => `/a/activity/activities/${id}/start`,
      finish: (id) => `/a/activity/activities/${id}/finish`,
      checkOuts: (id) => `/a/activity/activities/${id}/check-outs`,
      attendance: (id, volunteerId) => `/a/activity/activities/${id}/attendances/${volunteerId}`,
      violation: (id, volunteerId) => `/a/activity/activities/${id}/attendances/${volunteerId}/violations`,
      summary: (id) => `/a/activity/activities/${id}/summary`,
      serviceRecords: "/a/activity/service-records",
      serviceRecordsPending: "/a/activity/service-records/pending",
      attendanceConfirm: (id) => `/a/activity/attendances/${id}/confirm`,
      attendancePoints: (id) => `/a/activity/attendances/${id}/points`,
      attendanceChanges: "/a/activity/attendance-changes",
      attendanceChangeCreate: (id) => `/a/activity/attendances/${id}/changes`,
      attendanceChangeApprove: (id) => `/a/activity/attendance-changes/${id}/approve`,
      attendanceChangeReject: (id) => `/a/activity/attendance-changes/${id}/reject`,
      recurring: "/a/activity/activities/recurring",
      historical: "/a/activity/activities/historical",
      backfills: "/a/activity/backfills",
      backfillCreate: (id) => `/a/activity/activities/${id}/backfills`,
      backfillApprove: (id) => `/a/activity/backfills/${id}/approve`,
      backfillReject: (id) => `/a/activity/backfills/${id}/reject`,
      messageDelete: (id) => `/a/activity/messages/${id}`
    },
    organization: {
      subAccounts: "/a/organization/sub-accounts",
      subAccountDetail: (id) => `/a/organization/sub-accounts/${id}`,
      subAccountPermissions: (id) => `/a/organization/sub-accounts/${id}/permissions`,
      subAccountPasswordReset: (id) => `/a/organization/sub-accounts/${id}/password/reset`,
      permissions: "/a/organization/permissions",
      groups: "/a/organization/groups",
      groupLeader: (id) => `/a/organization/groups/${id}/leader`,
      groupLeaderHistory: (id) => `/a/organization/groups/${id}/leader-history`,
      groupsImport: "/a/organization/groups/import",
      groupApplications: "/a/organization/groups/applications",
      groupApplicationApprove: (id) => `/a/organization/groups/applications/${id}/approve`,
      groupApplicationReject: (id) => `/a/organization/groups/applications/${id}/reject`,
      squads: "/a/organization/squads",
      squadDetail: (id) => `/a/organization/squads/${id}`,
      squadApplications: (id) => `/a/organization/squads/${id}/applications`,
      squadApplicationApprove: (id) => `/a/organization/squads/applications/${id}/approve`,
      squadApplicationReject: (id) => `/a/organization/squads/applications/${id}/reject`,
      volunteerManagerFlag: (id) => `/a/organization/volunteers/${id}/manager-flag`,
      volunteerPermissions: (id) => `/a/organization/volunteers/${id}/permissions`,
      volunteerGrantablePermissions: "/a/organization/permissions/volunteer-grantable"
    },
    publicity: {
      banners: "/a/publicity/banners",
      bannerDetail: (id) => `/a/publicity/banners/${id}`,
      bannerSort: (id) => `/a/publicity/banners/${id}/sort`,
      announcements: "/a/publicity/announcements",
      announcementDetail: (id) => `/a/publicity/announcements/${id}`,
      files: "/a/publicity/files",
      fileDetail: (id) => `/a/publicity/files/${id}`,
      fileAccess: (id) => `/a/publicity/files/${id}/access`
    },
    data: {
      dashboard: "/a/data/dashboard"
    }
  }
};

module.exports = {
  ROLE_PREFIX,
  ENDPOINTS
};
