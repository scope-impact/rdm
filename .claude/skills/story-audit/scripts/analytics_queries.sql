-- =============================================================================
-- Backlog.md Analytics Queries (Schema v2.0.0)
-- Usage: duckdb backlog.duckdb < this_file.sql
-- =============================================================================

-- =============================================================================
-- SUMMARY STATISTICS
-- =============================================================================

-- Overall counts
SELECT 'projects' as table_name, COUNT(*) as count FROM projects
UNION ALL SELECT 'milestones', COUNT(*) FROM milestones
UNION ALL SELECT 'tasks', COUNT(*) FROM tasks
UNION ALL SELECT 'subtasks', COUNT(*) FROM subtasks
UNION ALL SELECT 'acceptance_criteria', COUNT(*) FROM acceptance_criteria
UNION ALL SELECT 'risks', COUNT(*) FROM risks
UNION ALL SELECT 'risk_controls', COUNT(*) FROM risk_controls
UNION ALL SELECT 'decisions', COUNT(*) FROM decisions
UNION ALL SELECT 'labels', COUNT(*) FROM labels;

-- Tasks by status with percentage
SELECT
    status,
    COUNT(*) as count,
    ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER(), 1) as pct
FROM tasks
GROUP BY status
ORDER BY count DESC;

-- Subtasks by status
SELECT
    status,
    COUNT(*) as count,
    ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER(), 1) as pct
FROM subtasks
GROUP BY status
ORDER BY count DESC;

-- =============================================================================
-- MILESTONE PROGRESS
-- =============================================================================

-- Milestone summary with task counts
SELECT
    m.local_id,
    m.title,
    m.status,
    COUNT(t.global_id) as total_tasks,
    SUM(CASE WHEN t.status = 'Done' THEN 1 ELSE 0 END) as done,
    SUM(CASE WHEN t.status = 'In Progress' THEN 1 ELSE 0 END) as in_progress,
    SUM(CASE WHEN t.status = 'To Do' THEN 1 ELSE 0 END) as todo,
    ROUND(
        SUM(CASE WHEN t.status = 'Done' THEN 1 ELSE 0 END) * 100.0 /
        NULLIF(COUNT(t.global_id), 0),
        1
    ) as pct_complete
FROM milestones m
LEFT JOIN tasks t ON t.milestone_id = m.global_id
GROUP BY m.global_id, m.local_id, m.title, m.status
ORDER BY m.local_id;

-- =============================================================================
-- TASK ANALYSIS
-- =============================================================================

-- Tasks with subtask progress
SELECT
    t.local_id,
    t.title,
    t.status,
    t.priority,
    t.subtask_count,
    (SELECT COUNT(*) FROM subtasks s
     WHERE s.parent_task_id = t.global_id AND s.status = 'Done') as subtasks_done,
    ROUND(
        (SELECT COUNT(*) FROM subtasks s
         WHERE s.parent_task_id = t.global_id AND s.status = 'Done') * 100.0 /
        NULLIF(t.subtask_count, 0),
        0
    ) as subtask_pct
FROM tasks t
ORDER BY t.local_id;

-- Tasks by priority
SELECT
    priority,
    status,
    COUNT(*) as count
FROM tasks
GROUP BY priority, status
ORDER BY
    CASE priority
        WHEN 'high' THEN 1
        WHEN 'medium' THEN 2
        WHEN 'low' THEN 3
        ELSE 4
    END,
    status;

-- High priority incomplete tasks
SELECT
    local_id,
    title,
    status,
    subtask_count
FROM tasks
WHERE priority = 'high'
  AND status != 'Done'
ORDER BY local_id;

-- =============================================================================
-- ACCEPTANCE CRITERIA ANALYSIS
-- =============================================================================

-- AC completion by task
SELECT
    t.local_id,
    t.title,
    t.acceptance_criteria_count as total_ac,
    t.completed_criteria_count as done_ac,
    ROUND(
        t.completed_criteria_count * 100.0 /
        NULLIF(t.acceptance_criteria_count, 0),
        0
    ) as pct_complete
FROM tasks t
WHERE t.acceptance_criteria_count > 0
ORDER BY pct_complete DESC, t.local_id;

-- AC completion by subtask
SELECT
    s.local_id,
    s.title,
    s.acceptance_criteria_count as total_ac,
    s.completed_criteria_count as done_ac,
    ROUND(
        s.completed_criteria_count * 100.0 /
        NULLIF(s.acceptance_criteria_count, 0),
        0
    ) as pct_complete
FROM subtasks s
WHERE s.acceptance_criteria_count > 0
ORDER BY pct_complete DESC, s.local_id;

-- Overall AC completion
SELECT
    'tasks' as type,
    SUM(acceptance_criteria_count) as total,
    SUM(completed_criteria_count) as completed,
    ROUND(SUM(completed_criteria_count) * 100.0 / NULLIF(SUM(acceptance_criteria_count), 0), 1) as pct
FROM tasks
UNION ALL
SELECT
    'subtasks',
    SUM(acceptance_criteria_count),
    SUM(completed_criteria_count),
    ROUND(SUM(completed_criteria_count) * 100.0 / NULLIF(SUM(acceptance_criteria_count), 0), 1)
FROM subtasks;

-- =============================================================================
-- RISK ANALYSIS
-- =============================================================================

-- Risks by severity
SELECT
    severity,
    COUNT(*) as count,
    ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER(), 1) as pct
FROM risks
WHERE severity IS NOT NULL
GROUP BY severity
ORDER BY
    CASE severity
        WHEN 'Critical' THEN 1
        WHEN 'High' THEN 2
        WHEN 'Medium' THEN 3
        WHEN 'Low' THEN 4
        ELSE 5
    END;

-- Risks by STRIDE category
SELECT
    stride_category,
    COUNT(*) as count
FROM risks
WHERE stride_category IS NOT NULL
GROUP BY stride_category
ORDER BY count DESC;

-- Risk mitigation status
SELECT
    mitigation_status,
    COUNT(*) as count,
    ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER(), 1) as pct
FROM risks
GROUP BY mitigation_status
ORDER BY count DESC;

-- Residual risk distribution
SELECT
    residual_risk,
    COUNT(*) as count
FROM risks
WHERE residual_risk IS NOT NULL
GROUP BY residual_risk
ORDER BY
    CASE residual_risk
        WHEN 'High' THEN 1
        WHEN 'Medium' THEN 2
        WHEN 'Low' THEN 3
        ELSE 4
    END;

-- High severity risks not fully mitigated
SELECT
    local_id,
    title,
    severity,
    mitigation_status,
    residual_risk
FROM risks
WHERE severity IN ('Critical', 'High')
  AND mitigation_status != 'Mitigated'
ORDER BY
    CASE severity WHEN 'Critical' THEN 1 ELSE 2 END,
    local_id;

-- Risk controls per risk
SELECT
    r.local_id,
    r.title,
    r.control_count,
    COUNT(rc.id) as actual_controls
FROM risks r
LEFT JOIN risk_controls rc ON rc.risk_id = r.global_id
GROUP BY r.global_id, r.local_id, r.title, r.control_count
ORDER BY r.local_id;

-- =============================================================================
-- DECISION ANALYSIS
-- =============================================================================

-- Decisions by status
SELECT
    status,
    COUNT(*) as count
FROM decisions
GROUP BY status
ORDER BY count DESC;

-- Recent decisions
SELECT
    local_id,
    title,
    date,
    status
FROM decisions
ORDER BY date DESC NULLS LAST
LIMIT 10;

-- =============================================================================
-- LABEL ANALYSIS
-- =============================================================================

-- Most common labels
SELECT
    name as label,
    COUNT(*) as usage_count
FROM labels
GROUP BY name
ORDER BY usage_count DESC
LIMIT 20;

-- Labels by task
SELECT
    t.local_id,
    t.title,
    array_to_string(t.labels, ', ') as labels
FROM tasks t
WHERE array_length(t.labels) > 0
ORDER BY t.local_id;

-- =============================================================================
-- CROSS-PROJECT QUERIES (for multi-project databases)
-- =============================================================================

-- Tasks by project
SELECT
    project_id,
    COUNT(*) as task_count
FROM tasks
GROUP BY project_id;

-- Project summary
SELECT
    p.project_id,
    p.project_name,
    (SELECT COUNT(*) FROM milestones m WHERE m.project_id = p.project_id) as milestones,
    (SELECT COUNT(*) FROM tasks t WHERE t.project_id = p.project_id) as tasks,
    (SELECT COUNT(*) FROM subtasks s WHERE s.project_id = p.project_id) as subtasks,
    (SELECT COUNT(*) FROM risks r WHERE r.project_id = p.project_id) as risks,
    (SELECT COUNT(*) FROM decisions d WHERE d.project_id = p.project_id) as decisions
FROM projects p;

-- =============================================================================
-- PROGRESS DASHBOARD
-- =============================================================================

-- Overall progress summary
SELECT
    (SELECT COUNT(*) FROM tasks WHERE status = 'Done') as tasks_done,
    (SELECT COUNT(*) FROM tasks) as tasks_total,
    (SELECT COUNT(*) FROM subtasks WHERE status = 'Done') as subtasks_done,
    (SELECT COUNT(*) FROM subtasks) as subtasks_total,
    ROUND(
        (SELECT COUNT(*) FROM tasks WHERE status = 'Done') * 100.0 /
        NULLIF((SELECT COUNT(*) FROM tasks), 0),
        1
    ) as task_pct,
    ROUND(
        (SELECT COUNT(*) FROM subtasks WHERE status = 'Done') * 100.0 /
        NULLIF((SELECT COUNT(*) FROM subtasks), 0),
        1
    ) as subtask_pct;

-- =============================================================================
-- EXPORT QUERIES
-- =============================================================================

-- Export task summary to CSV
-- COPY (
--     SELECT local_id, title, status, priority, subtask_count, acceptance_criteria_count
--     FROM tasks ORDER BY local_id
-- ) TO 'task_summary.csv' (HEADER, DELIMITER ',');

-- Export risk summary to CSV
-- COPY (
--     SELECT local_id, title, severity, mitigation_status, residual_risk
--     FROM risks ORDER BY local_id
-- ) TO 'risk_summary.csv' (HEADER, DELIMITER ',');
