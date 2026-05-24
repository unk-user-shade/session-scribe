package com.sessionscribe;

import java.util.ArrayList;
import java.util.List;

final class AccountHistory
{
	AllTimeStats allTime = new AllTimeStats();
	List<SessionRecord> sessions = new ArrayList<>();
	PendingSession pending; // nullable
}
