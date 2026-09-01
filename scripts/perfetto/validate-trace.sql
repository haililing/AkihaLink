SELECT
  name,
  ts,
  dur,
  process.name AS process_name,
  thread.name AS thread_name
FROM slice
JOIN thread_track ON slice.track_id = thread_track.id
JOIN thread USING (utid)
JOIN process USING (upid)
WHERE slice.name GLOB 'AKL/*'
ORDER BY ts;

-- One row per anonymous trace and stage, followed by a compact percentile
-- summary that can be compared across repeated startup/node-switch runs.
WITH stages AS (
  SELECT
    substr(name, 5, length(name) - 21) AS stage,
    substr(name, -16) AS trace_id,
    dur
  FROM slice
  WHERE name GLOB 'AKL/*/*'
    AND length(name) >= 21
), ranked AS (
  SELECT
    stage,
    trace_id,
    dur,
    row_number() OVER (PARTITION BY stage ORDER BY dur) AS rank,
    count(*) OVER (PARTITION BY stage) AS samples
  FROM stages
)
SELECT
  stage,
  count(DISTINCT trace_id) AS trace_count,
  max(samples) AS sample_count,
  max(CASE WHEN rank = (samples + 1) / 2 THEN dur END) AS p50_ns,
  max(CASE WHEN rank = (samples * 95 + 99) / 100 THEN dur END) AS p95_ns
FROM ranked
GROUP BY stage
ORDER BY stage;

SELECT
  CASE
    WHEN COUNT(*) = 0 THEN 'PASS'
    ELSE 'FAIL'
  END AS privacy_check
FROM slice
WHERE slice.name GLOB 'AKL/*'
  AND (
    slice.name GLOB '*.*.*'
    OR slice.name GLOB '*:*'
    OR slice.name GLOB '*"outbounds"*'
  );
