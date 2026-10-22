package dev.pironi.tool;

import dev.pironi.safety.AccessGrants;

/** A read tool whose reach follows the directories granted during the session. */
public interface GrantAware {
    void useGrants(AccessGrants sessionGrants);
}
