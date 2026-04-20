export const AuthProducts = {
  AUTH_SERVICE: 'AUTH_SERVICE',
  POCKETHIVE: 'POCKETHIVE',
  HIVEWATCH: 'HIVEWATCH',
} as const

export const AuthServicePermissionIds = {
  ADMIN: 'ADMIN',
} as const

export const AuthServiceResourceTypes = {
  GLOBAL: 'AUTH_GLOBAL',
} as const

export const AuthServiceResourceSelectors = {
  GLOBAL: '*',
} as const

export const PocketHivePermissionIds = {
  VIEW: 'VIEW',
  RUN: 'RUN',
  ALL: 'ALL',
} as const

export const PocketHiveResourceTypes = {
  DEPLOYMENT: 'PH_DEPLOYMENT',
  FOLDER: 'PH_FOLDER',
  BUNDLE: 'PH_BUNDLE',
} as const

export const PocketHiveResourceSelectors = {
  GLOBAL: '*',
} as const
