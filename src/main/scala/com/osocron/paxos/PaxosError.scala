package com.osocron.paxos

sealed trait PaxosError
case object MessageNotEnqueued extends PaxosError
case object UnexpectedError extends PaxosError
