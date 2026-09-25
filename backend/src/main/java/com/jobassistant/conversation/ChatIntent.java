package com.jobassistant.conversation;

public enum ChatIntent {
    /** A fresh job search. */
    NEW_SEARCH,
    /** Narrow or adjust the previous results ("only those above 15 LPA"). */
    REFINE,
    /** Compare specific jobs ("compare the first three"). */
    COMPARE,
    /** A question about one specific job ("what skills does this job require?"). */
    JOB_QUESTION,
    /** Greeting / help / anything that is not a search. */
    GENERAL
}
