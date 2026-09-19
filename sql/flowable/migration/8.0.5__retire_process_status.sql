-- C05: offline, one-way, lossy retirement of the processStatus process variable.
-- Stop all writers and verify the target database before execution.
-- Requires a verified full backup and an inventory of legacy consumers.
-- Abort on failure; do not force continuation.
-- Reverting application code cannot restore deleted historical variables;
-- recovery requires matching versions and the backup.
DELIMITER $$
DROP PROCEDURE IF EXISTS c05_retire_process_status$$
CREATE PROCEDURE c05_retire_process_status()
BEGIN
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        DROP TEMPORARY TABLE IF EXISTS c05_status_candidates;
        RESIGNAL;
    END;

    START TRANSACTION;
    -- Do not rewrite deployed BPMN or model bytes in place.
    IF EXISTS (
        SELECT 1 FROM ACT_GE_BYTEARRAY b
        WHERE (b.DEPLOYMENT_ID_ IS NOT NULL OR b.ID_ IN (
            SELECT EDITOR_SOURCE_VALUE_ID_ FROM ACT_RE_MODEL
            UNION SELECT EDITOR_SOURCE_EXTRA_VALUE_ID_ FROM ACT_RE_MODEL))
        AND LOCATE('processstatus', LOWER(CONVERT(b.BYTES_ USING utf8mb4))) > 0
    ) OR EXISTS (
        SELECT 1 FROM wf_model_design_draft
        WHERE LOCATE('processstatus', LOWER(bpmn_xml)) > 0
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'C05_DEPENDENCY: deploy/model resource references legacy status; review and drain first';
    END IF;

    -- Only the original root-scoped string protocol is eligible for automatic retirement.
    IF EXISTS (SELECT 1 FROM ACT_RU_VARIABLE WHERE NAME_ = 'processStatus'
        AND (TYPE_ <> 'string' OR TEXT_ IS NULL OR TASK_ID_ IS NOT NULL
             OR NOT (EXECUTION_ID_ <=> PROC_INST_ID_) OR SCOPE_ID_ IS NOT NULL
             OR SUB_SCOPE_ID_ IS NOT NULL OR SCOPE_TYPE_ IS NOT NULL
             OR BYTEARRAY_ID_ IS NOT NULL OR TEXT2_ IS NOT NULL OR LONG_ IS NOT NULL OR DOUBLE_ IS NOT NULL
             OR BINARY TEXT_ NOT IN ('running','returned','completed','canceled','rejected','terminated')))
    OR EXISTS (SELECT 1 FROM ACT_HI_VARINST WHERE NAME_ = 'processStatus'
        AND (VAR_TYPE_ IS NULL OR VAR_TYPE_ <> 'string' OR TEXT_ IS NULL OR TASK_ID_ IS NOT NULL
             OR NOT (EXECUTION_ID_ <=> PROC_INST_ID_) OR SCOPE_ID_ IS NOT NULL
             OR SUB_SCOPE_ID_ IS NOT NULL OR SCOPE_TYPE_ IS NOT NULL
             OR BYTEARRAY_ID_ IS NOT NULL OR TEXT2_ IS NOT NULL OR LONG_ IS NOT NULL OR DOUBLE_ IS NOT NULL
             OR BINARY TEXT_ NOT IN ('running','returned','completed','canceled','rejected','terminated')))
    OR EXISTS (SELECT 1 FROM ACT_HI_DETAIL WHERE NAME_ = 'processStatus'
        AND (TYPE_ <> 'VariableUpdate' OR VAR_TYPE_ IS NULL OR VAR_TYPE_ <> 'string'
             OR TASK_ID_ IS NOT NULL OR NOT (EXECUTION_ID_ <=> PROC_INST_ID_)
             OR BYTEARRAY_ID_ IS NOT NULL OR TEXT2_ IS NOT NULL OR LONG_ IS NOT NULL OR DOUBLE_ IS NOT NULL
             OR TEXT_ IS NULL OR BINARY TEXT_ NOT IN ('running','returned','completed','canceled','rejected','terminated')))
    THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'C05_STORAGE: unknown type, scope, value or storage slot; manual disposition required';
    END IF;

    IF EXISTS (SELECT PROC_INST_ID_ FROM ACT_RU_VARIABLE WHERE NAME_='processStatus'
        GROUP BY PROC_INST_ID_ HAVING COUNT(*) <> 1)
    OR EXISTS (SELECT PROC_INST_ID_ FROM ACT_HI_VARINST WHERE NAME_='processStatus'
        GROUP BY PROC_INST_ID_ HAVING COUNT(*) <> 1)
    OR EXISTS (SELECT 1 FROM ACT_RU_VARIABLE v LEFT JOIN ACT_RU_EXECUTION r
        ON r.ID_=v.PROC_INST_ID_ AND r.ID_=r.PROC_INST_ID_
        WHERE v.NAME_='processStatus' AND r.ID_ IS NULL)
    OR EXISTS (SELECT 1 FROM ACT_HI_VARINST v LEFT JOIN ACT_HI_PROCINST h ON h.ID_=v.PROC_INST_ID_
        WHERE v.NAME_='processStatus' AND h.ID_ IS NULL)
    OR EXISTS (SELECT 1 FROM ACT_HI_DETAIL v LEFT JOIN ACT_HI_PROCINST h ON h.ID_=v.PROC_INST_ID_
        WHERE v.NAME_='processStatus' AND h.ID_ IS NULL)
    OR EXISTS (SELECT 1 FROM ACT_RU_EXECUTION r LEFT JOIN ACT_HI_PROCINST h ON h.ID_=r.ID_
        WHERE r.ID_=r.PROC_INST_ID_ AND h.ID_ IS NULL)
    THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'C05_CARDINALITY: duplicate/orphan legacy records or missing process history';
    END IF;

    -- The temporary table exists only for this offline command; no ongoing second state source.
    CREATE TEMPORARY TABLE c05_status_candidates AS
    SELECT h.ID_ AS instance_id, h.BUSINESS_STATUS_ AS historic_status,
           r.BUSINESS_STATUS_ AS runtime_status, hv.TEXT_ AS historic_variable,
           rv.TEXT_ AS runtime_variable, h.STATE_ AS engine_state, h.END_TIME_ AS end_time,
           r.ID_ IS NOT NULL AS active,
           COALESCE(h.BUSINESS_STATUS_, r.BUSINESS_STATUS_, hv.TEXT_, rv.TEXT_) AS chosen
    FROM ACT_HI_PROCINST h
    LEFT JOIN ACT_RU_EXECUTION r ON r.ID_=h.ID_ AND r.ID_=r.PROC_INST_ID_
    LEFT JOIN ACT_HI_VARINST hv ON hv.PROC_INST_ID_=h.ID_ AND hv.NAME_='processStatus'
    LEFT JOIN ACT_RU_VARIABLE rv ON rv.PROC_INST_ID_=h.ID_ AND rv.NAME_='processStatus';

    IF EXISTS (SELECT 1 FROM c05_status_candidates WHERE chosen IS NULL
        OR BINARY chosen NOT IN ('running','returned','completed','canceled','rejected','terminated')
        OR (historic_status IS NOT NULL AND BINARY historic_status <> BINARY chosen)
        OR (runtime_status IS NOT NULL AND BINARY runtime_status <> BINARY chosen)
        OR (historic_variable IS NOT NULL AND BINARY historic_variable <> BINARY chosen)
        OR (runtime_variable IS NOT NULL AND BINARY runtime_variable <> BINARY chosen))
    THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'C05_CONFLICT: missing authority or disagreeing values; no automatic precedence between copies';
    END IF;
    -- Never infer canceled/rejected/terminated from an engine delete reason.
    IF EXISTS (SELECT 1 FROM c05_status_candidates WHERE
        (active=1 AND (end_time IS NOT NULL OR chosen NOT IN ('running','returned')))
        OR (active=0 AND (end_time IS NULL
            OR (chosen NOT IN ('canceled','rejected','terminated')
                AND (engine_state IS NULL OR engine_state <> 'completed'
                    OR chosen NOT IN ('running','completed'))))))
    THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'C05_LIFECYCLE: engine and business lifecycle need manual disposition';
    END IF;

    -- Preserve explicit business terminal priority; completed engine history overrides old running.
    UPDATE c05_status_candidates SET chosen='completed'
        WHERE active=0 AND engine_state='completed' AND end_time IS NOT NULL AND chosen='running';
    UPDATE ACT_HI_PROCINST h JOIN c05_status_candidates c ON c.instance_id=h.ID_
        SET h.BUSINESS_STATUS_=c.chosen WHERE NOT (h.BUSINESS_STATUS_ <=> c.chosen);
    UPDATE ACT_RU_EXECUTION r JOIN c05_status_candidates c ON c.instance_id=r.ID_
        SET r.BUSINESS_STATUS_=c.chosen, r.REV_=r.REV_+1
        WHERE NOT (r.BUSINESS_STATUS_ <=> c.chosen);
    DELETE FROM ACT_RU_VARIABLE WHERE NAME_='processStatus';
    DELETE FROM ACT_HI_VARINST WHERE NAME_='processStatus';
    DELETE FROM ACT_HI_DETAIL WHERE NAME_='processStatus';
    DROP TEMPORARY TABLE c05_status_candidates;
    COMMIT;
END$$
CALL c05_retire_process_status()$$
DROP PROCEDURE c05_retire_process_status$$
DELIMITER ;
