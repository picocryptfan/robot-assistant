package app.heyrobot.assistant

// VOICE PROCESS. Everything the assistant panel's parts can ask for, in one interface: the panel passes
// PanelActions.Default, which does each through PanelController, and the tests pass their own to see which one a tap
// asks for. Nothing here holds state or decides anything.

interface PanelActions {
    /** Sends what was typed (or the draft); false when it wasn't taken (a turn is still under way). */
    fun send(text: String): Boolean
    fun askAboutScreen(question: String)

    fun keepConversation()
    fun toggleSpeak()
    fun newConversation()

    fun dismissNotice()
    fun openRobotApp()
    fun openAssistSettings()
    fun openModelSettings()
    fun openPermissions()

    fun attachScreen()
    fun removeDraft()
    /** CaptureActivity.MODE_CAMERA or MODE_PHOTOS. */
    fun openCapture(mode: String)
    fun readClaudeAnswer()

    fun micPressed()
    fun micReleased()
    /** Stop: ends the turn (and declines a waiting card). */
    fun stopGenerating()
    /** Quiets the voice (and ends a reply still being written, 0.4.6). */
    fun stopSpeaking()

    fun confirm(actionId: String, yes: Boolean)
    fun choose(actionId: String, index: Int, remember: Boolean)
    fun undo(actionId: String)
    fun openHandOff(actionId: String)

    fun acceptOffer(replyId: String)
    fun dismissOffer(replyId: String)
    fun endHelperMode()

    /** What the panel does on screen: each action through PanelController. */
    companion object Default : PanelActions {
        override fun send(text: String) = PanelController.send(text)
        override fun askAboutScreen(question: String) { PanelController.askAboutScreen(question) }
        override fun keepConversation() { PanelController.keepConversation() }
        override fun toggleSpeak() { PanelController.toggleSpeak() }
        override fun newConversation() { PanelController.newConversation() }
        override fun dismissNotice() { PanelController.dismissNotice() }
        override fun openRobotApp() { PanelController.openRobotApp() }
        override fun openAssistSettings() { PanelController.openAssistSettings() }
        override fun openModelSettings() { PanelController.openModelSettings() }
        override fun openPermissions() { PanelController.openPermissions() }
        override fun attachScreen() { PanelController.attachScreen() }
        override fun removeDraft() { PanelController.removeDraft() }
        override fun openCapture(mode: String) { PanelController.openCapture(mode) }
        override fun readClaudeAnswer() { PanelController.readClaudeAnswer() }
        override fun micPressed() { PanelController.micPressed() }
        override fun micReleased() { PanelController.micReleased() }
        override fun stopGenerating() { PanelController.stopGenerating() }
        override fun stopSpeaking() { PanelController.stopSpeaking() }
        override fun confirm(actionId: String, yes: Boolean) { PanelController.confirm(actionId, yes) }
        override fun choose(actionId: String, index: Int, remember: Boolean) { PanelController.choose(actionId, index, remember) }
        override fun undo(actionId: String) { PanelController.undo(actionId) }
        override fun openHandOff(actionId: String) { PanelController.openHandOff(actionId) }
        override fun acceptOffer(replyId: String) { PanelController.acceptOffer(replyId) }
        override fun dismissOffer(replyId: String) { PanelController.dismissOffer(replyId) }
        override fun endHelperMode() { PanelController.endHelperMode() }
    }
}
