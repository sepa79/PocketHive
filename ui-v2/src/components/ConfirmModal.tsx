type ConfirmModalProps = {
  open: boolean
  title: string
  message: string
  confirmLabel?: string
  cancelLabel?: string
  danger?: boolean
  busy?: boolean
  onConfirm: () => void | Promise<void>
  onClose: () => void
}

export function ConfirmModal({
  open,
  title,
  message,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  danger = false,
  busy = false,
  onConfirm,
  onClose,
}: ConfirmModalProps) {
  if (!open) return null

  return (
    <div className="modalBackdrop" role="presentation" onClick={() => (!busy ? onClose() : undefined)}>
      <div className="modal" role="dialog" aria-modal="true" onClick={(event) => event.stopPropagation()}>
        <div className="modalHeader">
          <div>
            <div className="h2">{title}</div>
            <div className="muted">{message}</div>
          </div>
          <button type="button" className="actionButton actionButtonGhost" onClick={onClose} disabled={busy}>
            {cancelLabel}
          </button>
        </div>

        <div className="modalSection">
          <div className="row" style={{ justifyContent: 'flex-end', gap: 10 }}>
            <button type="button" className="actionButton actionButtonGhost" onClick={onClose} disabled={busy}>
              {cancelLabel}
            </button>
            <button
              type="button"
              className={danger ? 'actionButton actionButtonDanger' : 'actionButton'}
              onClick={() => void onConfirm()}
              disabled={busy}
            >
              {confirmLabel}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
