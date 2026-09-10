package com.merseyside.jobs

/**
 * A job's report on what it is busy with right now.
 *
 * The library does not look inside: whoever writes the job declares their own
 * type and casts the report back to it on the screen. Progress is never
 * stored — it is the course of the work, not its state.
 */
interface JobProgress
