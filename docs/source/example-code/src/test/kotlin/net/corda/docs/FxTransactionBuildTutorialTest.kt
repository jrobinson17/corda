package net.corda.docs

import com.google.common.util.concurrent.SettableFuture
import net.corda.core.contracts.*
import net.corda.core.getOrThrow
import net.corda.core.node.services.ServiceInfo
import net.corda.core.serialization.OpaqueBytes
import net.corda.core.utilities.DUMMY_NOTARY
import net.corda.core.utilities.DUMMY_NOTARY_KEY
import net.corda.flows.CashCommand
import net.corda.flows.CashFlow
import net.corda.node.services.network.NetworkMapService
import net.corda.node.services.transactions.ValidatingNotaryService
import net.corda.node.utilities.databaseTransaction
import net.corda.testing.node.MockNetwork
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class FxTransactionBuildTutorialTest {
    lateinit var net: MockNetwork
    lateinit var notaryNode: MockNetwork.MockNode
    lateinit var nodeA: MockNetwork.MockNode
    lateinit var nodeB: MockNetwork.MockNode

    @Before
    fun setup() {
        net = MockNetwork(threadPerNode = true)
        notaryNode = net.createNode(
                legalName = DUMMY_NOTARY.name,
                keyPair = DUMMY_NOTARY_KEY,
                advertisedServices = *arrayOf(ServiceInfo(NetworkMapService.type), ServiceInfo(ValidatingNotaryService.type)))
        nodeA = net.createPartyNode(notaryNode.info.address)
        nodeB = net.createPartyNode(notaryNode.info.address)
        FxTransactionDemoTutorial.registerFxProtocols(nodeA.services)
        FxTransactionDemoTutorial.registerFxProtocols(nodeB.services)
        WorkflowTransactionBuildTutorial.registerWorkflowProtocols(nodeA.services)
        WorkflowTransactionBuildTutorial.registerWorkflowProtocols(nodeB.services)
    }

    @After
    fun cleanUp() {
        println("Close DB")
        net.stopNodes()
    }

    @Test
    fun `Run ForeignExchangeFlow to completion`() {
        // Use NodeA as issuer and create some dollars
        val flowHandle1 = nodeA.services.startFlow(CashFlow(CashCommand.IssueCash(DOLLARS(1000),
                OpaqueBytes.of(0x01),
                nodeA.info.legalIdentity,
                notaryNode.info.notaryIdentity)))
        // Wait for the flow to stop and print
        flowHandle1.resultFuture.getOrThrow()
        printBalances()

        // Using NodeB as Issuer create some pounds.
        val flowHandle2 = nodeB.services.startFlow(CashFlow(CashCommand.IssueCash(POUNDS(1000),
                OpaqueBytes.of(0x01),
                nodeB.info.legalIdentity,
                notaryNode.info.notaryIdentity)))
        // Wait for flow to come to an end and print
        flowHandle2.resultFuture.getOrThrow()
        printBalances()

        // Setup some futures on the vaults to await the arrival of the exchanged funds at both nodes
        val done2 = SettableFuture.create<Unit>()
        val done3 = SettableFuture.create<Unit>()
        val subs2 = nodeA.services.vaultService.updates.subscribe {
            done2.set(Unit)
        }
        val subs3 = nodeB.services.vaultService.updates.subscribe {
            done3.set(Unit)
        }
        // Now run the actual Fx exchange
        val doIt = nodeA.services.startFlow(ForeignExchangeFlow("trade1",
                POUNDS(100).issuedBy(nodeB.info.legalIdentity.ref(0x01)),
                DOLLARS(200).issuedBy(nodeA.info.legalIdentity.ref(0x01)),
                nodeA.info.legalIdentity,
                nodeB.info.legalIdentity))
        // wait for the flow to finish and the vault updates to be done
        doIt.resultFuture.getOrThrow()
        // Get the balances when the vault updates
        done2.get()
        val balancesA = databaseTransaction(nodeA.database) {
            nodeA.services.vaultService.cashBalances
        }
        done3.get()
        val balancesB = databaseTransaction(nodeB.database) {
            nodeB.services.vaultService.cashBalances
        }
        subs2.unsubscribe()
        subs3.unsubscribe()
        println("BalanceA\n" + balancesA)
        println("BalanceB\n" + balancesB)
        // Verify the transfers occurred as expected
        assertEquals(POUNDS(100), balancesA[GBP])
        assertEquals(DOLLARS(1000 - 200), balancesA[USD])
        assertEquals(POUNDS(1000 - 100), balancesB[GBP])
        assertEquals(DOLLARS(200), balancesB[USD])
    }

    private fun printBalances() {
        // Print out the balances
        databaseTransaction(nodeA.database) {
            println("BalanceA\n" + nodeA.services.vaultService.cashBalances)
        }
        databaseTransaction(nodeB.database) {
            println("BalanceB\n" + nodeB.services.vaultService.cashBalances)
        }
    }
}