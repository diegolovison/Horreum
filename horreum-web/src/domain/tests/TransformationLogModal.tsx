import { useCallback } from "react"
import { NavLink } from "react-router-dom"

import {logApi, TransformationLog} from "../../api"
import { formatDateTime } from "../../utils"
import LogModal, { CommonLogModalProps, LogLevelIcon } from "../../components/LogModal"

type TransformationLogModalProps = {
    testId: number
    runId?: number
} & CommonLogModalProps

export default function TransformationLogModal(props: TransformationLogModalProps) {
    const fetchCount = useCallback(
        level => logApi.getTransformationLogCount(props.testId, level, props.runId),
        [props.testId]
    )
    const fetchRows = useCallback(
        (level, page, limit) =>
            logApi.getTransformationLog(props.testId, level, limit, page, props.runId).then(response =>
                (response as TransformationLog[]).map(log => ({
                    cells: [
                        { title: <LogLevelIcon level={log.level} /> },
                        { title: formatDateTime(log.timestamp) },
                        {
                            title: <NavLink to={`/run/${log.runId}#run`}>{log.runId}</NavLink>,
                        },
                        { title: <div dangerouslySetInnerHTML={{ __html: log.message }}></div> },
                    ],
                }))
            ),
        [props.testId]
    )
    const deleteFromTo = useCallback(
        (from, to) => logApi.deleteTransformationLogs(props.testId, from, props.runId, to),
        [props.testId]
    )
    return (
        <LogModal
            {...props}
            columns={["Level", "Timestamp", "Run ID", "Message"]}
            fetchCount={fetchCount}
            fetchLogs={fetchRows}
            deleteLogs={deleteFromTo}
        />
    )
}
