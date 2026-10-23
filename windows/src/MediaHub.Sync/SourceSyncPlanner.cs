using MediaHub.Domain;

namespace MediaHub.Sync;

/// <summary>
/// Turns an incoming <see cref="SourceSyncEnvelope"/> plus what this device already holds into a list of
/// suggestions. Pure: no I/O, no clock, no mutation.
/// </summary>
/// <remarks>
/// <para>
/// The rules, in the order they are applied to a record:
/// </para>
/// <list type="number">
/// <item><description>a wire <c>sourceType</c> this build does not know — or knows but cannot represent —
/// produces <see cref="SourceSyncAction.Unsupported"/> and the raw string is kept in the candidate, so a later
/// build can adopt it instead of the user re-entering the source (D3);</description></item>
/// <item><description>two local sources claiming the same <c>syncSourceId</c> is a conflict — the device's own data
/// is already inconsistent, so neither may win;</description></item>
/// <item><description>a higher <c>recordRevision</c> wins → <see cref="SourceSyncAction.Update"/>; equal revision
/// with identical content is <see cref="SourceSyncAction.NoChange"/>; equal revision with different content is a
/// <see cref="SourceSyncAction.Conflict"/>; lower revision is ignored as stale, because being older is not a
/// disagreement;</description></item>
/// <item><description>no <c>syncSourceId</c> match: a pre-W6 local source (no sync identity) may be the same
/// thing, but only if type, normalised address and account all agree. One candidate is proposed as
/// <see cref="SourceSyncAction.Adopt"/> with approval; several is a conflict — the contract never guesses
/// (D5).</description></item>
/// </list>
/// <para>
/// <b>No wall-clock comparison appears anywhere in this class.</b> <c>createdAtEpochMs</c> and
/// <c>deletedAtEpochMs</c> are deliberately not read: two devices' clocks disagree, and "last write wins" by
/// timestamp silently destroys the edit made on the slower clock (D5). Ordering comes from
/// <c>recordRevision</c>; genuine ties become conflicts for the user to settle.
/// </para>
/// </remarks>
public static class SourceSyncPlanner
{
    /// <summary>Builds the proposal for <paramref name="envelope"/> against <paramref name="localSources"/>.</summary>
    public static SourceSyncPlan Plan(SourceSyncEnvelope envelope, IReadOnlyList<LocalSourceRecord> localSources)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        ArgumentNullException.ThrowIfNull(localSources);

        var items = new List<SourceSyncCandidate>(envelope.Sources.Count + envelope.Deletions.Count);
        foreach (SourceRecord record in envelope.Sources)
        {
            items.Add(PlanRecord(record, localSources));
        }

        foreach (Tombstone tombstone in envelope.Deletions)
        {
            items.Add(PlanTombstone(tombstone, localSources));
        }

        return new SourceSyncPlan { Items = items };
    }

    private static SourceSyncCandidate PlanRecord(SourceRecord record, IReadOnlyList<LocalSourceRecord> locals)
    {
        WindowsSourceTypeResolution resolution = WindowsSourceTypeMap.Resolve(record.SourceType.Raw);
        string? primaryUrl = PrimaryNormalizedUrl(record);

        var baseline = new SourceSyncCandidate
        {
            SyncSourceId = record.SyncSourceId,
            SourceType = record.SourceType.Raw,
            IsKnownSourceType = resolution.IsKnown,
            DisplayName = record.DisplayName,
            NormalizedUrl = primaryUrl,
            RemoteRevision = record.RecordRevision,
        };

        if (!resolution.IsKnown)
        {
            // Kept, never dropped: the wire string survives into the candidate and the stored record (D3).
            return baseline with
            {
                Action = SourceSyncAction.Unsupported,
                Reason = SourceSyncReasons.UnknownSourceType,
            };
        }

        if (!resolution.IsSupported)
        {
            // Known to the protocol, absent from this client's model (fn-nas, the Android-only cloud drives).
            return baseline with
            {
                Action = SourceSyncAction.Unsupported,
                Reason = SourceSyncReasons.NotSupportedOnWindows,
            };
        }

        List<LocalSourceRecord> matches =
            locals.Where(local => SameSyncSourceId(local.SyncSourceId, record.SyncSourceId)).ToList();

        if (matches.Count > 1)
        {
            return baseline with
            {
                Action = SourceSyncAction.Conflict,
                Reason = SourceSyncReasons.DuplicateLocalSyncId,
                RequiresUserApproval = true,
                CandidateServerIds = matches.Select(local => local.ServerId).ToList(),
            };
        }

        if (matches.Count == 1)
        {
            LocalSourceRecord local = matches[0];

            if (record.RecordRevision > local.RecordRevision)
            {
                return baseline with
                {
                    Action = SourceSyncAction.Update,
                    Reason = SourceSyncReasons.HigherRevision,
                    ServerId = local.ServerId,
                    LocalRevision = local.RecordRevision,
                };
            }

            if (record.RecordRevision == local.RecordRevision)
            {
                bool sameContent = ContentMatches(record, resolution, local, primaryUrl);
                return baseline with
                {
                    Action = sameContent ? SourceSyncAction.NoChange : SourceSyncAction.Conflict,
                    Reason = sameContent
                        ? SourceSyncReasons.UpToDate
                        : SourceSyncReasons.SameRevisionDifferentContent,
                    ServerId = local.ServerId,
                    LocalRevision = local.RecordRevision,
                    RequiresUserApproval = !sameContent,
                };
            }

            return baseline with
            {
                Action = SourceSyncAction.IgnoreStale,
                Reason = SourceSyncReasons.StaleRevision,
                ServerId = local.ServerId,
                LocalRevision = local.RecordRevision,
            };
        }

        // No sync identity match. A pre-W6 local record has none at all, so it can only be suggested —
        // and only when all three identity components agree and the answer is unique (D5).
        List<LocalSourceRecord> legacy = localSourcesLegacyMatches(record, primaryUrl, locals);

        if (legacy.Count == 1)
        {
            return baseline with
            {
                Action = SourceSyncAction.Adopt,
                Reason = SourceSyncReasons.LegacyIdentityMatch,
                ServerId = legacy[0].ServerId,
                LocalRevision = legacy[0].RecordRevision,
                RequiresUserApproval = true,
            };
        }

        if (legacy.Count > 1)
        {
            return baseline with
            {
                Action = SourceSyncAction.Conflict,
                Reason = SourceSyncReasons.AmbiguousLegacyIdentity,
                RequiresUserApproval = true,
                CandidateServerIds = legacy.Select(local => local.ServerId).ToList(),
            };
        }

        if (primaryUrl is null)
        {
            // Creating a source with no dialable address would produce an entry the user can only delete.
            return baseline with
            {
                Action = SourceSyncAction.Create,
                Reason = SourceSyncReasons.NoUsableEndpoint,
                RequiresUserApproval = true,
            };
        }

        return baseline with
        {
            Action = SourceSyncAction.Create,
            Reason = SourceSyncReasons.NewSource,
        };
    }

    private static List<LocalSourceRecord> localSourcesLegacyMatches(
        SourceRecord record,
        string? primaryUrl,
        IReadOnlyList<LocalSourceRecord> locals)
    {
        // Both addresses are required: matching on type and account alone would merge two different servers
        // of the same kind that the same user happens to use.
        if (primaryUrl is null)
        {
            return [];
        }

        return locals.Where(local =>
        {
            if (!string.IsNullOrWhiteSpace(local.SyncSourceId))
            {
                return false;
            }

            string? localWire = WindowsSourceTypeMap.ToWire(local.Type);
            if (localWire is null)
            {
                return false;
            }

            if (!string.Equals(localWire, record.SourceType.Wire, StringComparison.Ordinal))
            {
                return false;
            }

            if (!ScopesEqual(local.AccountScope, record.AccountScope))
            {
                return false;
            }

            return string.Equals(SyncUrlNormalizer.Normalize(local.Url), primaryUrl, StringComparison.Ordinal);
        }).ToList();
    }

    private static SourceSyncCandidate PlanTombstone(Tombstone tombstone, IReadOnlyList<LocalSourceRecord> locals)
    {
        var baseline = new SourceSyncCandidate
        {
            SyncSourceId = tombstone.SyncSourceId,
            RemoteRevision = tombstone.RecordRevision,
        };

        List<LocalSourceRecord> matches =
            locals.Where(local => SameSyncSourceId(local.SyncSourceId, tombstone.SyncSourceId)).ToList();

        if (matches.Count == 0)
        {
            // Deleting something this device never had is not an error, and not a decision either.
            return baseline with
            {
                Action = SourceSyncAction.NoChange,
                Reason = SourceSyncReasons.NoLocalSource,
            };
        }

        if (matches.Count > 1)
        {
            return baseline with
            {
                Action = SourceSyncAction.Conflict,
                Reason = SourceSyncReasons.DuplicateLocalSyncId,
                RequiresUserApproval = true,
                CandidateServerIds = matches.Select(local => local.ServerId).ToList(),
            };
        }

        LocalSourceRecord match = matches[0];

        if (tombstone.RecordRevision < match.RecordRevision)
        {
            // The local record was edited after the remote deletion was made: a stale deletion must not
            // propose removing live data.
            return baseline with
            {
                Action = SourceSyncAction.IgnoreStale,
                Reason = SourceSyncReasons.StaleRevision,
                ServerId = match.ServerId,
                LocalRevision = match.RecordRevision,
            };
        }

        return baseline with
        {
            Action = SourceSyncAction.Delete,
            Reason = SourceSyncReasons.Tombstone,
            ServerId = match.ServerId,
            LocalRevision = match.RecordRevision,
            // Never silent: the local copy may be the only one left, and its credentials go with it (D5, D6).
            RequiresUserApproval = true,
        };
    }

    private static bool ContentMatches(
        SourceRecord record,
        WindowsSourceTypeResolution resolution,
        LocalSourceRecord local,
        string? primaryUrl)
    {
        if (resolution.ServerType is null)
        {
            return false;
        }

        if (!string.Equals(
                WindowsSourceTypeMap.ToWire(resolution.ServerType.Value),
                record.SourceType.Wire,
                StringComparison.Ordinal))
        {
            return false;
        }

        if (!ScopesEqual(local.AccountScope, record.AccountScope))
        {
            return false;
        }

        if (!string.Equals(local.DisplayName ?? "", record.DisplayName, StringComparison.Ordinal))
        {
            return false;
        }

        return string.Equals(SyncUrlNormalizer.Normalize(local.Url), primaryUrl, StringComparison.Ordinal);
    }

    private static string? PrimaryNormalizedUrl(SourceRecord record)
    {
        SourceEndpoint? endpoint = SelectPrimaryEndpoint(record);
        return endpoint is null ? null : SyncUrlNormalizer.Normalize(endpoint.Url);
    }

    private static SourceEndpoint? SelectPrimaryEndpoint(SourceRecord record)
    {
        if (record.Endpoints.Count == 0)
        {
            return null;
        }

        // D2 allows exactly one primary. If a sender marks several, the smallest sortOrder wins and LINQ's
        // stable ordering keeps the sender's order between equals — never an arbitrary pick.
        SourceEndpoint? primary = record.Endpoints
            .Where(endpoint => endpoint is not null && endpoint.IsPrimary)
            .OrderBy(endpoint => endpoint.SortOrder)
            .FirstOrDefault();

        return primary ?? record.Endpoints
            .Where(endpoint => endpoint is not null)
            .OrderBy(endpoint => endpoint.SortOrder)
            .FirstOrDefault();
    }

    private static bool SameSyncSourceId(string? left, string? right)
        => !string.IsNullOrWhiteSpace(left)
            && !string.IsNullOrWhiteSpace(right)
            && string.Equals(left.Trim(), right.Trim(), StringComparison.OrdinalIgnoreCase);

    private static bool ScopesEqual(string? left, string? right)
        => string.Equals(NormalizeScope(left), NormalizeScope(right), StringComparison.OrdinalIgnoreCase);

    private static string? NormalizeScope(string? accountScope)
        => string.IsNullOrWhiteSpace(accountScope) ? null : accountScope.Trim();
}
