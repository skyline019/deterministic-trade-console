import { demoGateway } from './demo'
import { httpGateway } from './http'
import type { TradeGateway } from './types'

export const isDemoMode = import.meta.env.VITE_DEMO_MODE === 'true'
export const gateway: TradeGateway = isDemoMode ? demoGateway : httpGateway
