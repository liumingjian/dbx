import { useEffect, useState } from 'react';
import { Modal, Stack, TextInput } from '@carbon/react';
import type { AddCredentialVersionRequest, DatabaseConnection } from '@/contract';
import { messages } from '@/messages';

/**
 * Maintaining a credential adds a 凭据版本; it never edits one.
 *
 * The store therefore also resets the connection's 最近校验 to `NOT_RUN`: the previous
 * check authenticated with a version that is no longer current, so reporting it as still
 * valid would be a claim DBX has not observed.
 */
export function AddCredentialVersionModal({
  connection,
  onClose,
  onSubmit,
}: {
  connection: DatabaseConnection | null;
  onClose: () => void;
  onSubmit: (id: string, request: AddCredentialVersionRequest) => void;
}) {
  const [username, setUsername] = useState('');
  const [secret, setSecret] = useState('');

  useEffect(() => {
    setUsername(connection?.currentCredentialVersion.username ?? '');
    setSecret('');
  }, [connection]);

  const complete = username.trim() !== '' && secret !== '';
  const close = () => {
    setSecret('');
    onClose();
  };

  // Mounted only while open, for the reason recorded in `RegisterConnectionModal`.
  if (connection === null) {
    return null;
  }

  return (
    <Modal
      open
      modalHeading={messages.connections.addCredentialVersion.title}
      modalLabel={connection.name}
      primaryButtonText={messages.connections.addCredentialVersion.submit}
      secondaryButtonText={messages.connections.addCredentialVersion.cancel}
      primaryButtonDisabled={!complete}
      onRequestClose={close}
      onSecondarySubmit={close}
      onRequestSubmit={() => {
        if (!complete) return;
        onSubmit(connection.id, { username: username.trim(), secret });
        setSecret('');
      }}
    >
      <p className="dbx-modal__description">
        {messages.connections.addCredentialVersion.description}
      </p>
      <Stack gap={5}>
        <TextInput
          id="dbx-credential-username"
          labelText={messages.connections.addCredentialVersion.usernameLabel}
          value={username}
          onChange={(event) => setUsername(event.target.value)}
        />
        <TextInput
          type="password"
          id="dbx-credential-secret"
          labelText={messages.connections.addCredentialVersion.secretLabel}
          value={secret}
          onChange={(event) => setSecret(event.target.value)}
        />
      </Stack>
    </Modal>
  );
}
