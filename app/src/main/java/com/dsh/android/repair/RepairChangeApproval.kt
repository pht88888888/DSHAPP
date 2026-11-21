package com.dsh.android.repair

import java.util.UUID

/** One-use activation ticket, held only in the target :repair process after native consent. */
internal object RepairChangeApproval {
    private data class Ticket(val grant: String, val method: String, val change: String, val expires: Long, val binding: String)
    private val tickets = mutableMapOf<String, Ticket>()

    @Synchronized fun issue(grant: String, method: String, change: String, now: Long, binding: String = ""): String {
        tickets.entries.removeAll { it.value.expires <= now }
        check(tickets.size < 16) { "Too many pending approvals" }
        return UUID.randomUUID().toString().also {
            tickets[it] = Ticket(grant, method, change, now + 60_000L, binding)
        }
    }

    @Synchronized fun consume(token: String, grant: String, method: String, change: String, now: Long, binding: String = ""): Boolean {
        val ticket = tickets.remove(token) ?: return false
        return ticket.grant == grant && ticket.method == method && ticket.change == change && ticket.binding == binding &&
            now >= ticket.expires - 60_000L && now < ticket.expires
    }
}
