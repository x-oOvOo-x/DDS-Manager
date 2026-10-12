# DDS Manager — Catalogue des traductions

**142 clés actives** dans chaque langue (`fr_FR`, `en_US`, `zh_CN`).

Les fichiers se trouvent dans `plugins/dds-manager/lang/`. Chaque clé peut être personnalisée sans recompiler.
Les paramètres `{server}`, `{player}`, etc. sont remplacés au moment de l’affichage ; conservez leurs noms.
Les balises de couleur autorisées (par exemple `<green>` ou `<yellow>`) sont prises en charge. Les textes techniques comme `group:Public` et les commandes ne sont pas traduits.

## Correspondance des clés

### Autorisations (14)

| Clé | Texte français | Variables | Appel dans le code |
|---|---|---|---|
| `access.reason.admin-node` | Permission administrateur | — | `service/AccessService.java` |
| `access.reason.admin-uuid` | UUID administrateur intégré | — | `service/AccessService.java` |
| `access.reason.bypass` | Permission de contournement de la whitelist | — | `service/AccessService.java` |
| `access.reason.global` | Whitelist globale | — | `service/AccessService.java` |
| `access.reason.group` | Whitelist du groupe {value} | `{value}` | `service/AccessService.java` |
| `access.reason.legacy-group` | Ancienne whitelist du groupe {value} | `{value}` | `service/AccessService.java` |
| `access.reason.legacy-server` | Ancienne whitelist du serveur {value} | `{value}` | `service/AccessService.java` |
| `access.reason.no-grant` | Aucune autorisation dans la whitelist | — | `service/AccessService.java` |
| `access.reason.no-match` | Aucune whitelist ni permission pour {value} | `{value}` | `service/AccessService.java` |
| `access.reason.permission` | Permission {value} | `{value}` | `service/AccessService.java` |
| `access.reason.server` | Whitelist du serveur {value} | `{value}` | `service/AccessService.java` |
| `access.reason.uuid-mismatch` | UUID du profil absent ou différent | — | `service/AccessService.java` |
| `access.reason.uuid-pending` | En attente de la première connexion et de l’association de l’UUID | — | `service/AccessService.java` |
| `access.reason.whitelist-off` | Whitelist désactivée | — | `service/AccessService.java` |

### Annonces (2)

| Clé | Texte français | Variables | Appel dans le code |
|---|---|---|---|
| `alert.header` | [Annonce]  | — | `command/DdsCommand.java` |
| `alert.sent` | Annonce envoyée. | — | `command/DdsCommand.java` |

### Chat et canaux (4)

| Clé | Texte français | Variables | Appel dans le code |
|---|---|---|---|
| `channel.changed` | Canal actif : {channel} | `{channel}` | `command/DdsCommand.java` |
| `channel.current` | Canal actuel : {channel} | `{channel}` | `command/DdsCommand.java` |
| `channel.invalid` | Choisis local ou global. | — | `command/DdsCommand.java` |
| `channel.players-only` | Cette commande est réservée aux joueurs. | — | `command/DdsCommand.java` |

### Messages communs (14)

| Clé | Texte français | Variables | Appel dans le code |
|---|---|---|---|
| `common.add` | [Ajouter] | — | `command/DdsCommand.java` |
| `common.allowed` | Autorisé | — | `command/DdsCommand.java` |
| `common.denied` | Refusé | — | `command/DdsCommand.java` |
| `common.group-missing` | Groupe introuvable : {group} | `{group}` | `command/DdsCommand.java` |
| `common.more` | [Suivant] | — | `command/DdsCommand.java` |
| `common.no` | désactivée | — | `command/DdsCommand.java` |
| `common.permission-missing` | Permission manquante : {permission} | `{permission}` | `command/DdsCommand.java` |
| `common.player-detail-hover` | Cliquer pour voir les informations du joueur | — | `command/DdsCommand.java` |
| `common.player-missing` | Joueur introuvable : {player} | `{player}` | `command/DdsCommand.java` |
| `common.previous` | [Précédent] | — | `command/DdsCommand.java` |
| `common.remove` | [Retirer] | — | `command/DdsCommand.java` |
| `common.server-missing` | Serveur introuvable : {server} | `{server}` | `command/DdsCommand.java` |
| `common.username-invalid` | Pseudo Java invalide : {player} | `{player}` | `command/DdsCommand.java` |
| `common.yes` | activée | — | `command/DdsCommand.java` |

### Confirmations (5)

| Clé | Texte français | Variables | Appel dans le code |
|---|---|---|---|
| `confirm.button` | [Confirmer] | — | `command/DdsCommand.java` |
| `confirm.click` | Cliquer pour confirmer | — | `command/DdsCommand.java` |
| `confirm.danger` | Opération sensible : {description} | `{description}` | `command/DdsCommand.java` |
| `confirm.invalid` | Confirmation invalide ou expirée. | — | `command/DdsCommand.java` |

### Connexion (3)

| Clé | Texte français | Variables | Appel dans le code |
|---|---|---|---|
| `connection.no-access` | Tu n'as accès à aucun serveur. | — | `listener/ConnectionListener.java` |
| `connection.offline-blocked` | DDS Manager exige une authentification officielle via Velocity. | — | `listener/ConnectionListener.java` |
| `connection.server-denied` | Tu n'as pas la permission d'accéder à {server}. | `{server}` | `listener/ConnectionListener.java` |

### Groupes de serveurs (16)

| Clé | Texte français | Variables | Appel dans le code |
|---|---|---|---|
| `group.added` | {changed} serveur(s) ajouté(s) au groupe {group}. | `{changed}`, `{group}` | `command/DdsCommand.java` |
| `group.create-first` | Groupe {group} introuvable. Crée-le d’abord avec /dds group create {group}. | `{group}` | `command/DdsCommand.java` |
| `group.created` | Groupe créé : {group}. | `{group}` | `command/DdsCommand.java` |
| `group.delete-prompt` | Supprimer le groupe de serveurs | — | `command/DdsCommand.java` |
| `group.deleted` | Groupe {group} supprimé : {cleaned} autorisation(s) nettoyée(s), {disconnected} déconnexion(s). | `{cleaned}`, `{disconnected}`, `{group}` | `command/DdsCommand.java` |
| `group.details` | {group} : {servers} | `{group}`, `{servers}` | `command/DdsCommand.java` |
| `group.empty` | Aucun groupe de serveurs. | — | `command/DdsCommand.java` |
| `group.exists` | Ce groupe existe déjà : {group} | `{group}` | `command/DdsCommand.java` |
| `group.hover` | Serveurs : {servers} | `{servers}` | `command/DdsCommand.java` |
| `group.remove-prompt` | Retirer des serveurs du groupe | — | `command/DdsCommand.java` |
| `group.removed` | {changed} serveur(s) retiré(s) du groupe {group}, {disconnected} joueur(s) déconnecté(s). | `{changed}`, `{disconnected}`, `{group}` | `command/DdsCommand.java` |
| `group.server-notfound` | Serveur introuvable : {server}. Aucune modification effectuée. | `{server}` | `command/DdsCommand.java` |
| `group.usage-add` | Utilisation : /dds group add <group> <server...> | — | `command/DdsCommand.java` |
| `group.usage-create` | Utilisation : /dds group create <group> | — | `command/DdsCommand.java` |
| `group.usage-delete` | Utilisation : /dds group delete <group> | — | `command/DdsCommand.java` |
| `group.usage-remove` | Utilisation : /dds group remove <group> <server...> | — | `command/DdsCommand.java` |

### Profils joueurs (17)

| Clé | Texte français | Variables | Appel dans le code |
|---|---|---|---|
| `player.add-global-hover` | Ajouter une autorisation globale | — | `command/DdsCommand.java` |
| `player.allowed-origin` | {state} ↵ Source : {reason} | `{reason}`, `{state}` | `command/DdsCommand.java` |
| `player.detail` | {player} UUID={uuid}, canal={channel}, dernier serveur={lastServer} | `{channel}`, `{lastServer}`, `{player}`, `{uuid}` | `command/DdsCommand.java` |
| `player.global-grant` | Autorisation globale : {status} | `{status}` | `command/DdsCommand.java` |
| `player.grant-origin` | Autorisations enregistrées : {scopes} | `{scopes}` | `command/DdsCommand.java` |
| `player.granted` | active | — | `command/DdsCommand.java` |
| `player.grants` | Autorisations : {scopes} | `{scopes}` | `command/DdsCommand.java` |
| `player.group-access` | Accès par groupes : | — | `command/DdsCommand.java` |
| `player.group-add-hover` | Ajouter l’autorisation du groupe | — | `command/DdsCommand.java` |
| `player.group-member-hover` | Serveurs du groupe : {servers} | `{servers}` | `command/DdsCommand.java` |
| `player.group-remove-hover` | Retirer l’autorisation du groupe {group} à {player} | `{group}`, `{player}` | `command/DdsCommand.java` |
| `player.not-granted` | aucune | — | `command/DdsCommand.java` |
| `player.remove-profile` | [Supprimer le profil] | — | `command/DdsCommand.java` |
| `player.remove-profile-hover` | Supprimer définitivement le profil DDS de {player} | `{player}` | `command/DdsCommand.java` |
| `player.server-access` | Accès aux serveurs : | — | `command/DdsCommand.java` |
| `player.server-add-hover` | Ajouter l’autorisation d’accès au serveur | — | `command/DdsCommand.java` |
| `player.server-remove-hover` | Retirer l’autorisation de {player} sur {server} | `{player}`, `{server}` | `command/DdsCommand.java` |

### Présence, Tablist et transferts (13)

| Clé | Texte français | Variables | Appel dans le code |
|---|---|---|---|
| `presence.click` |  ↵ Cliquer pour rejoindre le serveur | — | `service/NetworkPresenceService.java` |
| `presence.dimension-waiting` | En attente des données Join Game / Respawn | — | `service/NetworkPresenceService.java` |
| `presence.dimension.end` | End | — | `service/NetworkPresenceService.java` |
| `presence.dimension.nether` | Nether | — | `service/NetworkPresenceService.java` |
| `presence.dimension.overworld` | Monde normal | — | `service/NetworkPresenceService.java` |
| `presence.dimension.unknown` | Inconnue | — | `service/NetworkPresenceService.java` |
| `presence.join-prefix` | <yellow>{player} a rejoint </yellow> | `{player}` | `listener/ServerSwitchListener.java` |
| `presence.leave-prefix` | <yellow>{player} a quitté </yellow> | `{player}` | `listener/ConnectionListener.java` |
| `presence.server-hover` | {server} ↵ Dimension : {dimension}{click} | `{click}`, `{dimension}`, `{server}` | `service/NetworkPresenceService.java` |
| `presence.switch-hover` | Serveur : {server}{click} | `{click}`, `{server}` | `service/NetworkPresenceService.java` |
| `presence.switch-prefix` | <gray>{player} </gray> | `{player}` | `listener/ServerSwitchListener.java` |
| `presence.tab-dimension` | Dimension : {dimension} ↵ Identifiant : {signal} | `{dimension}`, `{signal}` | `service/NetworkPresenceService.java` |
| `presence.tab-server` | Serveur : {server} | `{server}` | `service/NetworkPresenceService.java` |

### Rechargement (4)

| Clé | Texte français | Variables | Appel dans le code |
|---|---|---|---|
| `reload.disconnected` | Configuration rechargée : tu n’as plus accès aux serveurs. | — | `DdsManagerPlugin.java` |
| `reload.ok` | Configuration rechargée. | — | `command/DdsCommand.java` |
| `reload.prompt` | Recharger la configuration DDS | — | `command/DdsCommand.java` |
| `reload.warn` | Configuration rechargée, mais erreur de synchronisation de la whitelist. | — | `command/DdsCommand.java` |

### Historique des joueurs (3)

| Clé | Texte français | Variables | Appel dans le code |
|---|---|---|---|
| `seen.last-server` | , dernier serveur : {server} | `{server}` | `command/DdsCommand.java` |
| `seen.offline` | {player} vu pour la dernière fois le {date}{server}. | `{date}`, `{player}`, `{server}` | `command/DdsCommand.java` |
| `seen.online` | {player} est connecté sur {server}. | `{player}`, `{server}` | `command/DdsCommand.java` |

### Serveurs (7)

| Clé | Texte français | Variables | Appel dans le code |
|---|---|---|---|
| `server.details` | {server} : {count} joueur(s) en ligne, adresse {address}, groupes {groups}, secours {fallback} | `{address}`, `{count}`, `{fallback}`, `{groups}`, `{server}` | `command/DdsCommand.java` |
| `server.hover` | En ligne : {count} ; groupes : {groups}{fallback} | `{count}`, `{fallback}`, `{groups}` | `command/DdsCommand.java` |
| `server.item-fallback` | , serveur de secours | — | `command/DdsCommand.java` |
| `server.item-groups` | , groupes : {groups} | `{groups}` | `command/DdsCommand.java` |
| `server.list-item` | Joueurs en ligne : {players}{groups}{fallback} | `{fallback}`, `{groups}`, `{players}` | `command/DdsCommand.java` |
| `server.players` | Joueurs :  | — | `command/DdsCommand.java` |
| `server.summary` | {count} serveurs, {players} joueurs en ligne. | `{count}`, `{players}` | `command/DdsCommand.java` |

### Statut (1)

| Clé | Texte français | Variables | Appel dans le code |
|---|---|---|---|
| `status.summary` | Whitelist={whitelist}, dernier serveur={lastServer}, chat={chat}, Tab={tab}, minimap={minimap}, admins={admins}, groupes={groups}, tâches={tasks}, logs en attente={logQueue}, logs perdus={logDropped}, audits perdus={auditDropped} | `{admins}`, `{auditDropped}`, `{chat}`, `{groups}`, `{lastServer}`, `{logDropped}`, `{logQueue}`, `{minimap}`, `{tab}`, `{tasks}`, `{whitelist}` | `command/DdsCommand.java` |

### Tâches asynchrones (3)

| Clé | Texte français | Variables | Appel dans le code |
|---|---|---|---|
| `task.denied` | Tâche annulée : session expirée ou permissions révoquées. | — | `command/DdsCommand.java` |
| `task.failed` | Échec de la tâche d’administration : {error} | `{error}` | `command/DdsCommand.java` |
| `task.queue-full` | La file des tâches est pleine ou en cours d’arrêt. Réessaie plus tard. | — | `command/DdsCommand.java` |

### Aide des commandes (3)

| Clé | Texte français | Variables | Appel dans le code |
|---|---|---|---|
| `usage.alert` | Utilisation : /dds alert <message...> | — | `command/DdsCommand.java` |
| `usage.player` | Utilisation : /dds player <player> | — | `command/DdsCommand.java` |
| `usage.seen` | Utilisation : /dds seen <player> | — | `command/DdsCommand.java` |

### Validation (1)

| Clé | Texte français | Variables | Appel dans le code |
|---|---|---|---|
| `validation.group` | Nom de groupe invalide : lettres, chiffres, _, -, . uniquement (1 à 32 caractères). | — | `command/DdsCommand.java` |

### Whitelist et transferts (32)

| Clé | Texte français | Variables | Appel dans le code |
|---|---|---|---|
| `whitelist.access-revoked` | La whitelist a changé : accès au serveur actuel révoqué. | — | `command/DdsCommand.java` |
| `whitelist.action-add` | Ajout | — | `command/DdsCommand.java` |
| `whitelist.action-remove` | Retrait | — | `command/DdsCommand.java` |
| `whitelist.check` | {player} → {server} : {result} ; raison : {reason}{suffix} | `{player}`, `{reason}`, `{result}`, `{server}`, `{suffix}` | `command/DdsCommand.java` |
| `whitelist.check-offline` |  (joueur déconnecté : contrôle des autorisations enregistrées uniquement) | — | `command/DdsCommand.java` |
| `whitelist.check-usage` | Utilisation : /dds whitelist check <player> <server> | — | `command/DdsCommand.java` |
| `whitelist.detail-hover` | Cliquer pour voir le joueur ↵ Autorisations : {scopes} | `{scopes}` | `command/DdsCommand.java` |
| `whitelist.disable-prompt` | Désactiver la whitelist réseau | — | `command/DdsCommand.java` |
| `whitelist.disabled` | Whitelist réseau désactivée. | — | `command/DdsCommand.java` |
| `whitelist.enable-prompt` | Activer la whitelist réseau | — | `command/DdsCommand.java` |
| `whitelist.enabled` | Whitelist réseau activée. {disconnected} joueur(s) déconnecté(s). | `{disconnected}` | `command/DdsCommand.java` |
| `whitelist.export-error` | Échec de l’export : {error} | `{error}` | `command/DdsCommand.java` |
| `whitelist.exported` | {players} autorisation(s) exportée(s) vers transfer/{file}. | `{file}`, `{players}` | `command/DdsCommand.java` |
| `whitelist.file-failed` | Échec d’écriture de whitelist.json | — | `command/DdsCommand.java` |
| `whitelist.import-error` | Échec de l’import : {error} | `{error}` | `command/DdsCommand.java` |
| `whitelist.import-prompt` | Importer les autorisations de whitelist | — | `command/DdsCommand.java` |
| `whitelist.imported` | Import terminé : {changed} profil(s) modifié(s), {added} autorisation(s) ajoutée(s), {invalidPlayers} pseudo(s) invalide(s), {invalidScopes} autorisation(s) invalide(s), {failed} enregistrement(s) échoué(s). {file} | `{added}`, `{changed}`, `{failed}`, `{file}`, `{invalidPlayers}`, `{invalidScopes}` | `command/DdsCommand.java` |
| `whitelist.mutation` | {action} terminé : {changed} joueur(s) modifié(s), {missing} introuvable(s), {invalid} pseudo(s) invalide(s), {failed} erreur(s), {disconnected} déconnexion(s). {file} | `{action}`, `{changed}`, `{disconnected}`, `{failed}`, `{file}`, `{invalid}`, `{missing}` | `command/DdsCommand.java` |
| `whitelist.no-grants` | Aucune autorisation enregistrée. | — | `command/DdsCommand.java` |
| `whitelist.page` | Autorisations enregistrées : page {page}/{pages}, {players} joueur(s). | `{page}`, `{pages}`, `{players}` | `command/DdsCommand.java` |
| `whitelist.page-range` | Page valide : 1 à {pages} | `{pages}` | `command/DdsCommand.java` |
| `whitelist.purge` | Suppression complète : {removed} profil(s) effacé(s), {missing} introuvable(s), {invalid} invalide(s), {failed} erreur(s), {disconnected} déconnexion(s). {file} | `{disconnected}`, `{failed}`, `{file}`, `{invalid}`, `{missing}`, `{removed}` | `command/DdsCommand.java` |
| `whitelist.purged-kick` | Ton profil DDS Manager a été supprimé. | — | `command/DdsCommand.java` |
| `whitelist.record-removed` | Ton autorisation DDS Manager a été supprimée. | — | `command/DdsCommand.java` |
| `whitelist.remove-prompt` | Retirer les autorisations de whitelist | — | `command/DdsCommand.java` |
| `whitelist.status` | Whitelist réseau : {status}, permissionBasedAccess={permissions}, whitelist.json={file} | `{file}`, `{permissions}`, `{status}` | `command/DdsCommand.java` |
| `whitelist.sync.created` | Whitelist créée avec {written} joueur(s). | `{written}` | `DdsManagerPlugin.java` |
| `whitelist.sync.failed` | Synchronisation échouée, autorisations conservées : {error} | `{error}` | `DdsManagerPlugin.java` |
| `whitelist.sync.partial` | Synchronisation partielle : {added} ajout(s), {removed} retrait(s), {failed} erreur(s). | `{added}`, `{failed}`, `{removed}` | `DdsManagerPlugin.java` |
| `whitelist.sync.pending` | Synchronisation en attente | — | `DdsManagerPlugin.java` |
| `whitelist.sync.success` | Whitelist synchronisée : {added} ajout(s), {removed} retrait(s), {written} joueur(s). | `{added}`, `{removed}`, `{written}` | `DdsManagerPlugin.java` |
| `whitelist.sync.written` | Whitelist enregistrée : {written} joueur(s). | `{written}` | `DdsManagerPlugin.java` |

## Notes de maintenance

- Les fichiers livrés ont les mêmes clés et les mêmes variables dans les trois langues.
- Le rechargement conserve les textes personnalisés et ajoute les nouvelles clés absentes.
- Cinq clés anciennes sans référence Java ont été retirées des fichiers embarqués : `common.no-such-group`, `presence.dimension-missing`, `presence.join`, `presence.leave` et `presence.switch`. Si elles sont déjà présentes dans un fichier utilisateur, elles sont simplement ignorées ; elles ne sont pas effacées.
- La locale de la console et la locale d’un joueur sont indépendantes.
- Les messages natifs de Minecraft/Purpur ne sont pas traduits par DDS Manager.
