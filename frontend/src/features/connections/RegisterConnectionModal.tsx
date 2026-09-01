import { useState } from 'react';
import { Modal, Select, SelectItem, Stack, TextInput } from '@carbon/react';
import type { ConnectionRole, RegisterDatabaseConnectionRequest, TlsMode } from '@/contract';
import { messages } from '@/messages';

/**
 * Registering a 数据库连接, including its first 凭据版本.
 *
 * `CONTEXT.md` puts connection creation and credential entry here and nowhere else: the
 * migration wizard must never offer a place to type a credential into. The secret field is
 * labelled by the thing it creates — a 凭据版本 — rather than as a password field, which
 * the glossary lists under `_Avoid_` precisely because a credential is an immutable
 * version rather than a mutable current value.
 */

const emptyForm = {
  name: '',
  role: 'SOURCE' as ConnectionRole,
  host: '',
  port: '3306',
  database: '',
  username: '',
  tls: 'SERVER_AUTHENTICATED' as TlsMode,
  secret: '',
};

export function RegisterConnectionModal({
  open,
  onClose,
  onSubmit,
}: {
  open: boolean;
  onClose: () => void;
  onSubmit: (request: RegisterDatabaseConnectionRequest) => void;
}) {
  const [form, setForm] = useState(emptyForm);
  const set = <K extends keyof typeof emptyForm>(key: K, value: (typeof emptyForm)[K]) =>
    setForm((current) => ({ ...current, [key]: value }));

  const port = Number.parseInt(form.port, 10);
  const complete =
    form.name.trim() !== '' &&
    form.host.trim() !== '' &&
    form.database.trim() !== '' &&
    form.username.trim() !== '' &&
    form.secret !== '' &&
    Number.isInteger(port) &&
    port > 0;

  const close = () => {
    setForm(emptyForm);
    onClose();
  };

  // Mounted only while open. A closed Carbon modal keeps its fields in the document, and
  // two modals with a 凭据版本 field each would leave the page with two controls of the
  // same name — invisible to an operator, but ambiguous to a test and to a screen reader.
  if (!open) {
    return null;
  }

  return (
    <Modal
      open={open}
      modalHeading={messages.connections.register.title}
      modalLabel={messages.connections.title}
      primaryButtonText={messages.connections.register.submit}
      secondaryButtonText={messages.connections.register.cancel}
      primaryButtonDisabled={!complete}
      onRequestClose={close}
      onSecondarySubmit={close}
      onRequestSubmit={() => {
        if (!complete) return;
        onSubmit({
          name: form.name.trim(),
          role: form.role,
          host: form.host.trim(),
          port,
          database: form.database.trim(),
          username: form.username.trim(),
          tls: form.tls,
          secret: form.secret,
        });
        setForm(emptyForm);
      }}
    >
      <p className="dbx-modal__description">{messages.connections.register.description}</p>
      <Stack gap={5}>
        <TextInput
          id="dbx-connection-name"
          labelText={messages.connections.register.nameLabel}
          value={form.name}
          onChange={(event) => set('name', event.target.value)}
        />
        <Select
          id="dbx-connection-role"
          labelText={messages.connections.register.roleLabel}
          value={form.role}
          onChange={(event) => {
            const role = event.target.value as ConnectionRole;
            set('role', role);
            set('port', role === 'SOURCE' ? '3306' : '5432');
          }}
        >
          <SelectItem value="SOURCE" text={messages.connections.roles.source} />
          <SelectItem value="TARGET" text={messages.connections.roles.target} />
        </Select>
        <TextInput
          id="dbx-connection-host"
          labelText={messages.connections.register.hostLabel}
          value={form.host}
          onChange={(event) => set('host', event.target.value)}
        />
        <TextInput
          id="dbx-connection-port"
          labelText={messages.connections.register.portLabel}
          value={form.port}
          onChange={(event) => set('port', event.target.value)}
        />
        <TextInput
          id="dbx-connection-database"
          labelText={messages.connections.register.databaseLabel}
          value={form.database}
          onChange={(event) => set('database', event.target.value)}
        />
        <TextInput
          id="dbx-connection-username"
          labelText={messages.connections.register.usernameLabel}
          value={form.username}
          onChange={(event) => set('username', event.target.value)}
        />
        <Select
          id="dbx-connection-tls"
          labelText={messages.connections.register.tlsLabel}
          value={form.tls}
          onChange={(event) => set('tls', event.target.value as TlsMode)}
        >
          <SelectItem value="DISABLED" text={messages.connections.register.tlsModes.disabled} />
          <SelectItem
            value="SERVER_AUTHENTICATED"
            text={messages.connections.register.tlsModes.serverAuthenticated}
          />
          <SelectItem value="MUTUAL" text={messages.connections.register.tlsModes.mutual} />
        </Select>
        <TextInput
          type="password"
          id="dbx-connection-secret"
          labelText={messages.connections.register.secretLabel}
          helperText={messages.connections.register.secretHelper}
          value={form.secret}
          onChange={(event) => set('secret', event.target.value)}
        />
      </Stack>
    </Modal>
  );
}
